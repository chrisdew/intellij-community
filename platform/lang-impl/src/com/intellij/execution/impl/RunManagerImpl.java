/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.execution.impl;

import com.intellij.ProjectTopics;
import com.intellij.execution.*;
import com.intellij.execution.configurations.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.UnknownFeaturesCollector;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.JDOMExternalizableStringList;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.util.EventDispatcher;
import com.intellij.util.IconUtil;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.WeakHashMap;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

@State(
  name = "RunManager",
  defaultStateAsResource = true,
  storages = @Storage(StoragePathMacros.WORKSPACE_FILE)
)
public abstract class RunManagerImpl extends RunManagerEx implements PersistentStateComponent<Element>, NamedComponent, Disposable {
  protected static final Logger LOG = Logger.getInstance(RunManagerImpl.class);

  final Project myProject;

  private final Map<String, ConfigurationType> myTypesByName = new LinkedHashMap<>();

  protected final Map<String, RunnerAndConfigurationSettings> myTemplateConfigurationsMap = new ConcurrentSkipListMap<>();
  private final Map<String, RunnerAndConfigurationSettings> myConfigurations =
    new LinkedHashMap<>(); // template configurations are not included here
  private final Map<String, Boolean> mySharedConfigurations = new ConcurrentHashMap<>();
  private final Map<RunConfiguration, List<BeforeRunTask>> myConfigurationToBeforeTasksMap = new WeakHashMap<>();

  // When readExternal not all configuration may be loaded, so we need to remember the selected configuration
  // so that when it is eventually loaded, we can mark is as a selected.
  @Nullable private String myLoadedSelectedConfigurationUniqueName = null;
  @Nullable private String mySelectedConfigurationId = null;

  private final TimedIconCache myIconCache = new TimedIconCache();

  @NonNls
  protected static final String CONFIGURATION = "configuration";
  protected static final String RECENT = "recent_temporary";
  private ConfigurationType[] myTypes;
  private final RunManagerConfig myConfig;
  @NonNls
  protected static final String NAME_ATTR = "name";
  @NonNls
  protected static final String SELECTED_ATTR = "selected";
  @NonNls private static final String METHOD = "method";
  @NonNls private static final String OPTION = "option";

  private List<Element> myUnknownElements = null;
  private final JDOMExternalizableStringList myOrder = new JDOMExternalizableStringList();
  private final ArrayList<RunConfiguration> myRecentlyUsedTemporaries = new ArrayList<>();
  private boolean myOrdered = true;

  private final EventDispatcher<RunManagerListener> myDispatcher = EventDispatcher.create(RunManagerListener.class);

  public RunManagerImpl(@NotNull Project project, @NotNull PropertiesComponent propertiesComponent) {
    myConfig = new RunManagerConfig(propertiesComponent);
    myProject = project;

    initializeConfigurationTypes(ConfigurationType.CONFIGURATION_TYPE_EP.getExtensions());
    myProject.getMessageBus().connect().subscribe(ProjectTopics.PROJECT_ROOTS, new ModuleRootListener() {
      @Override
      public void rootsChanged(ModuleRootEvent event) {
        RunnerAndConfigurationSettings configuration = getSelectedConfiguration();
        if (configuration != null) {
          myIconCache.remove(configuration.getUniqueID());
        }
      }
    });
  }

  public static RunManagerImpl getInstanceImpl(@NotNull Project project) {
    return (RunManagerImpl)RunManager.getInstance(project);
  }

  // separate method needed for tests
  public final void initializeConfigurationTypes(@NotNull final ConfigurationType[] factories) {
    Arrays.sort(factories, Comparator.comparing(ConfigurationType::getDisplayName));

    final ArrayList<ConfigurationType> types = new ArrayList<>(Arrays.asList(factories));
    types.add(UnknownConfigurationType.INSTANCE);
    myTypes = types.toArray(new ConfigurationType[types.size()]);

    for (final ConfigurationType type : factories) {
      myTypesByName.put(type.getId(), type);
    }

    final UnknownConfigurationType broken = UnknownConfigurationType.INSTANCE;
    myTypesByName.put(broken.getId(), broken);
  }

  @Override
  @NotNull
  public RunnerAndConfigurationSettings createConfiguration(@NotNull final String name, @NotNull final ConfigurationFactory factory) {
    return createConfiguration(doCreateConfiguration(name, factory, true), factory);
  }

  protected RunConfiguration doCreateConfiguration(@NotNull String name, @NotNull ConfigurationFactory factory, final boolean fromTemplate) {
    if (fromTemplate) {
      return factory.createConfiguration(name, getConfigurationTemplate(factory).getConfiguration());
    }
    else {
      final RunConfiguration configuration = factory.createTemplateConfiguration(myProject, this);
      configuration.setName(name);
      return configuration;
    }
  }

  @Override
  @NotNull
  public RunnerAndConfigurationSettings createConfiguration(@NotNull final RunConfiguration runConfiguration,
                                                            @NotNull final ConfigurationFactory factory) {
    RunnerAndConfigurationSettings template = getConfigurationTemplate(factory);
    RunnerAndConfigurationSettingsImpl settings = new RunnerAndConfigurationSettingsImpl(this, runConfiguration, false);
    settings.importRunnerAndConfigurationSettings((RunnerAndConfigurationSettingsImpl)template);
    if (!mySharedConfigurations.containsKey(settings.getUniqueID())) {
      shareConfiguration(settings, isConfigurationShared(template));
    }
    return settings;
  }

  @Override
  public void dispose() {
    myTemplateConfigurationsMap.clear();
  }

  @Override
  public RunManagerConfig getConfig() {
    return myConfig;
  }

  @Override
  @NotNull
  public ConfigurationType[] getConfigurationFactories() {
    return myTypes.clone();
  }

  public ConfigurationType[] getConfigurationFactories(final boolean includeUnknown) {
    final ConfigurationType[] configurationTypes = myTypes.clone();
    if (!includeUnknown) {
      final List<ConfigurationType> types = new ArrayList<>();
      for (ConfigurationType configurationType : configurationTypes) {
        if (!(configurationType instanceof UnknownConfigurationType)) {
          types.add(configurationType);
        }
      }

      return types.toArray(new ConfigurationType[types.size()]);
    }

    return configurationTypes;
  }

  /**
   * Template configuration is not included
   */
  @Override
  @NotNull
  public List<RunConfiguration> getConfigurationsList(@NotNull ConfigurationType type) {
    List<RunConfiguration> result = null;
    for (RunnerAndConfigurationSettings settings : getSortedConfigurations()) {
      RunConfiguration configuration = settings.getConfiguration();
      if (type.getId().equals(configuration.getType().getId())) {
        if (result == null) {
          result = new SmartList<>();
        }
        result.add(configuration);
      }
    }
    return ContainerUtil.notNullize(result);
  }

  @Override
  @NotNull
  public List<RunConfiguration> getAllConfigurationsList() {
    Collection<RunnerAndConfigurationSettings> sortedConfigurations = getSortedConfigurations();
    if (sortedConfigurations.isEmpty()) {
      return Collections.emptyList();
    }

    List<RunConfiguration> result = new ArrayList<>(sortedConfigurations.size());
    for (RunnerAndConfigurationSettings settings : sortedConfigurations) {
      result.add(settings.getConfiguration());
    }
    return result;
  }

  @NotNull
  @Override
  public RunConfiguration[] getAllConfigurations() {
    List<RunConfiguration> list = getAllConfigurationsList();
    return list.toArray(new RunConfiguration[list.size()]);
  }

  @NotNull
  @Override
  public List<RunnerAndConfigurationSettings> getAllSettings() {
    return new ArrayList<>(getSortedConfigurations());
  }

  @Nullable
  public RunnerAndConfigurationSettings getSettings(@Nullable RunConfiguration configuration) {
    if (configuration == null) {
      return null;
    }

    for (RunnerAndConfigurationSettings settings : getSortedConfigurations()) {
      if (settings.getConfiguration() == configuration) {
        return settings;
      }
    }
    return null;
  }

  /**
   * Template configuration is not included
   */
  @Override
  @NotNull
  public List<RunnerAndConfigurationSettings> getConfigurationSettingsList(@NotNull ConfigurationType type) {
    List<RunnerAndConfigurationSettings> result = new SmartList<>();
    for (RunnerAndConfigurationSettings configuration : getSortedConfigurations()) {
      ConfigurationType configurationType = configuration.getType();
      if (configurationType != null && type.getId().equals(configurationType.getId())) {
        result.add(configuration);
      }
    }
    return result;
  }

  @NotNull
  @Override
  public RunnerAndConfigurationSettings[] getConfigurationSettings(@NotNull ConfigurationType type) {
    List<RunnerAndConfigurationSettings> list = getConfigurationSettingsList(type);
    return list.toArray(new RunnerAndConfigurationSettings[list.size()]);
  }

  @NotNull
  @Override
  public RunConfiguration[] getConfigurations(@NotNull ConfigurationType type) {
    RunnerAndConfigurationSettings[] settings = getConfigurationSettings(type);
    RunConfiguration[] result = new RunConfiguration[settings.length];
    for (int i = 0; i < settings.length; i++) {
      result[i] = settings[i].getConfiguration();
    }
    return result;
  }

  @NotNull
  @Override
  public Map<String, List<RunnerAndConfigurationSettings>> getStructure(@NotNull ConfigurationType type) {
    LinkedHashMap<String, List<RunnerAndConfigurationSettings>> map = new LinkedHashMap<>();
    List<RunnerAndConfigurationSettings> typeList = new ArrayList<>();
    List<RunnerAndConfigurationSettings> settings = getConfigurationSettingsList(type);
    for (RunnerAndConfigurationSettings setting : settings) {
      String folderName = setting.getFolderName();
      if (folderName == null) {
        typeList.add(setting);
      }
      else {
        List<RunnerAndConfigurationSettings> list = map.get(folderName);
        if (list == null) {
          map.put(folderName, list = new ArrayList<>());
        }
        list.add(setting);
      }
    }
    LinkedHashMap<String, List<RunnerAndConfigurationSettings>> result = new LinkedHashMap<>();
    for (Map.Entry<String, List<RunnerAndConfigurationSettings>> entry : map.entrySet()) {
      result.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
    }
    result.put(null, Collections.unmodifiableList(typeList));
    return Collections.unmodifiableMap(result);
  }

  @Override
  @NotNull
  public RunnerAndConfigurationSettings getConfigurationTemplate(@NotNull ConfigurationFactory factory) {
    RunnerAndConfigurationSettings template = myTemplateConfigurationsMap.get(factory.getType().getId() + "." + factory.getName());
    if (template == null) {
      template = new RunnerAndConfigurationSettingsImpl(this, factory.createTemplateConfiguration(myProject, this), true);
      template.setSingleton(factory.isConfigurationSingletonByDefault());
      if (template.getConfiguration() instanceof UnknownRunConfiguration) {
        ((UnknownRunConfiguration)template.getConfiguration()).setDoNotStore(true);
      }

      myTemplateConfigurationsMap.put(factory.getType().getId() + "." + factory.getName(), template);
    }
    return template;
  }

  @Override
  public void addConfiguration(RunnerAndConfigurationSettings settings, boolean shared, List<BeforeRunTask> tasks, boolean addEnabledTemplateTasksIfAbsent) {
    String existingId = findExistingConfigurationId(settings);
    String newId = settings.getUniqueID();
    RunnerAndConfigurationSettings existingSettings = null;

    if (existingId != null) {
      existingSettings = myConfigurations.remove(existingId);
      mySharedConfigurations.remove(existingId);
    }

    if (mySelectedConfigurationId != null && mySelectedConfigurationId.equals(existingId)) {
      setSelectedConfigurationId(newId);
    }
    myConfigurations.put(newId, settings);

    RunConfiguration configuration = settings.getConfiguration();
    if (existingId == null) {
      refreshUsagesList(configuration);
    }
    checkRecentsLimit();

    mySharedConfigurations.put(newId, shared);
    if (shared) {
      settings.setTemporary(false);
    }
    setBeforeRunTasks(configuration, tasks, addEnabledTemplateTasksIfAbsent);

    if (existingSettings == settings) {
      myDispatcher.getMulticaster().runConfigurationChanged(settings, existingId);
    }
    else {
      runConfigurationAdded(settings, shared);
    }
  }

  protected void runConfigurationAdded(@NotNull RunnerAndConfigurationSettings settings, boolean shared) {
    myDispatcher.getMulticaster().runConfigurationAdded(settings);
  }

  @Override
  public void refreshUsagesList(RunProfile profile) {
    if (!(profile instanceof RunConfiguration)) return;
    RunnerAndConfigurationSettings settings = getSettings((RunConfiguration)profile);
    if (settings != null && settings.isTemporary()) {
      myRecentlyUsedTemporaries.remove((RunConfiguration)profile);
      myRecentlyUsedTemporaries.add(0, (RunConfiguration)profile);
      trimUsagesListToLimit();
    }
  }

  private void trimUsagesListToLimit() {
    while(myRecentlyUsedTemporaries.size() > getConfig().getRecentsLimit()) {
      myRecentlyUsedTemporaries.remove(myRecentlyUsedTemporaries.size() - 1);
    }
  }

  void checkRecentsLimit() {
    trimUsagesListToLimit();
    List<RunnerAndConfigurationSettings> removed = new SmartList<>();
    while (getTempConfigurationsList().size() > getConfig().getRecentsLimit()) {
      for (Iterator<RunnerAndConfigurationSettings> it = myConfigurations.values().iterator(); it.hasNext(); ) {
        RunnerAndConfigurationSettings configuration = it.next();
        if (configuration.isTemporary() && !myRecentlyUsedTemporaries.contains(configuration.getConfiguration())) {
          removed.add(configuration);
          it.remove();
          break;
        }
      }
    }
    fireRunConfigurationsRemoved(removed);
  }

  public void setOrdered(boolean ordered) {
    myOrdered = ordered;
  }

  public void saveOrder() {
    setOrder(null);
  }

  private void doSaveOrder(@Nullable Comparator<RunnerAndConfigurationSettings> comparator) {
    List<RunnerAndConfigurationSettings> sorted = new ArrayList<>(
      ContainerUtil.filter(myConfigurations.values(), o -> !(o.getType() instanceof UnknownConfigurationType)));
    if (comparator != null) sorted.sort(comparator);
    
    myOrder.clear();
    for (RunnerAndConfigurationSettings each : sorted) {
      myOrder.add(each.getUniqueID());
    }
  }

  public void setOrder(@Nullable Comparator<RunnerAndConfigurationSettings> comparator) {
    doSaveOrder(comparator);
    setOrdered(false);// force recache of configurations list
  }

  @Override
  public void removeConfiguration(@Nullable RunnerAndConfigurationSettings settings) {
    if (settings == null) return;

    for (Iterator<RunnerAndConfigurationSettings> it = getSortedConfigurations().iterator(); it.hasNext(); ) {
      final RunnerAndConfigurationSettings configuration = it.next();
      if (configuration.equals(settings)) {
        if (mySelectedConfigurationId != null && mySelectedConfigurationId == settings.getUniqueID()) {
          setSelectedConfiguration(null);
        }

        it.remove();
        mySharedConfigurations.remove(settings.getUniqueID());
        myConfigurationToBeforeTasksMap.remove(settings.getConfiguration());
        myRecentlyUsedTemporaries.remove(settings.getConfiguration());
        myDispatcher.getMulticaster().runConfigurationRemoved(configuration);
        break;
      }
    }
    for (Map.Entry<RunConfiguration, List<BeforeRunTask>> entry : myConfigurationToBeforeTasksMap.entrySet()) {
      for (Iterator<BeforeRunTask> iterator = entry.getValue().iterator(); iterator.hasNext(); ) {
        BeforeRunTask task = iterator.next();
        if (task instanceof RunConfigurationBeforeRunProvider.RunConfigurableBeforeRunTask &&
            settings.equals(((RunConfigurationBeforeRunProvider.RunConfigurableBeforeRunTask)task).getSettings())) {
          iterator.remove();
          RunnerAndConfigurationSettings changedSettings = getSettings(entry.getKey());
          if (changedSettings != null) {
            myDispatcher.getMulticaster().runConfigurationChanged(changedSettings, null);
          }
        }
      }
    }
  }

  @Override
  @Nullable
  public RunnerAndConfigurationSettings getSelectedConfiguration() {
    if (mySelectedConfigurationId == null && myLoadedSelectedConfigurationUniqueName != null) {
      setSelectedConfigurationId(myLoadedSelectedConfigurationUniqueName);
    }
    return mySelectedConfigurationId == null ? null : myConfigurations.get(mySelectedConfigurationId);
  }

  @Override
  public void setSelectedConfiguration(@Nullable RunnerAndConfigurationSettings settings) {
    setSelectedConfigurationId(settings == null ? null : settings.getUniqueID());
    fireRunConfigurationSelected();
  }

  private void setSelectedConfigurationId(@Nullable String id) {
    mySelectedConfigurationId = id;
    if (mySelectedConfigurationId != null) myLoadedSelectedConfigurationUniqueName = null;
  }

  @Override
  @NotNull
  public Collection<RunnerAndConfigurationSettings> getSortedConfigurations() {
    if (myOrdered) {
      return myConfigurations.values();
    }

    List<Pair<String, RunnerAndConfigurationSettings>> order
      = new ArrayList<>(myConfigurations.size());
    final List<String> folderNames = new SmartList<>();
    for (RunnerAndConfigurationSettings each : myConfigurations.values()) {
      order.add(Pair.create(each.getUniqueID(), each));
      String folderName = each.getFolderName();
      if (folderName != null && !folderNames.contains(folderName)) {
        folderNames.add(folderName);
      }
    }
    folderNames.add(null);
    myConfigurations.clear();

    if (myOrder.isEmpty()) {
      // IDEA-63663 Sort run configurations alphabetically if clean checkout
      Collections.sort(order, (o1, o2) -> {
        boolean temporary1 = o1.getSecond().isTemporary();
        boolean temporary2 = o2.getSecond().isTemporary();
        if (temporary1 == temporary2) {
          return o1.first.compareTo(o2.first);
        }
        else {
          return temporary1 ? 1 : -1;
        }
      });
    }
    else {
      Collections.sort(order, (o1, o2) -> {
        int i1 = folderNames.indexOf(o1.getSecond().getFolderName());
        int i2 = folderNames.indexOf(o2.getSecond().getFolderName());
        if (i1 != i2) {
          return i1 - i2;
        }
        boolean temporary1 = o1.getSecond().isTemporary();
        boolean temporary2 = o2.getSecond().isTemporary();
        if (temporary1 == temporary2) {
          int index1 = myOrder.indexOf(o1.first);
          int index2 = myOrder.indexOf(o2.first);
          if (index1 == -1 && index2 == -1) {
            return o1.second.getName().compareTo(o2.second.getName());
          }
          return index1 - index2;
        }
        else {
          return temporary1 ? 1 : -1;
        }
      });
    }

    for (Pair<String, RunnerAndConfigurationSettings> each : order) {
      RunnerAndConfigurationSettings setting = each.second;
      myConfigurations.put(setting.getUniqueID(), setting);
    }

    myOrdered = true;
    return myConfigurations.values();
  }

  public static boolean canRunConfiguration(@NotNull ExecutionEnvironment environment) {
    RunnerAndConfigurationSettings runnerAndConfigurationSettings = environment.getRunnerAndConfigurationSettings();
    return runnerAndConfigurationSettings != null && canRunConfiguration(runnerAndConfigurationSettings, environment.getExecutor());
  }

  public static boolean canRunConfiguration(@NotNull RunnerAndConfigurationSettings configuration, @NotNull Executor executor) {
    try {
      configuration.checkSettings(executor);
    }
    catch (IndexNotReadyException ignored) {
      return Registry.is("dumb.aware.run.configurations");
    }
    catch (RuntimeConfigurationError ignored) {
      return false;
    }
    catch (RuntimeConfigurationException ignored) {
      return true;
    }
    return true;
  }

  @Nullable
  public Element getState(@NotNull Element parentNode) {
    if (myConfigurations.size() > 1) {
      JDOMExternalizableStringList order = null;
      for (RunnerAndConfigurationSettings each : myConfigurations.values()) {
        if (each.getType() instanceof UnknownConfigurationType) {
          continue;
        }

        if (order == null) {
          order = new JDOMExternalizableStringList();
        }
        order.add(each.getUniqueID());
      }
      if (order != null) {
        order.writeExternal(parentNode);
      }
    }

    final JDOMExternalizableStringList recentList = new JDOMExternalizableStringList();
    for (RunConfiguration each : myRecentlyUsedTemporaries) {
      if (each.getType() instanceof UnknownConfigurationType) {
        continue;
      }
      RunnerAndConfigurationSettings settings = getSettings(each);
      if (settings == null) {
        continue;
      }
      recentList.add(settings.getUniqueID());
    }
    if (!recentList.isEmpty()) {
      Element recent = new Element(RECENT);
      parentNode.addContent(recent);
      recentList.writeExternal(recent);
    }

    if (myUnknownElements != null) {
      for (Element unloadedElement : myUnknownElements) {
        parentNode.addContent(unloadedElement.clone());
      }
    }
    return parentNode;
  }

  public void writeContext(@NotNull Element element) {
    Collection<RunnerAndConfigurationSettings> values = new ArrayList<>(myConfigurations.values());
    for (RunnerAndConfigurationSettings configurationSettings : values) {
      if (configurationSettings.isTemporary()) {
        addConfigurationElement(element, configurationSettings, CONFIGURATION);
      }
    }

    RunnerAndConfigurationSettings selected = getSelectedConfiguration();
    if (selected != null) {
      element.setAttribute(SELECTED_ATTR, selected.getUniqueID());
    }
  }

  void addConfigurationElement(@NotNull Element parentNode, RunnerAndConfigurationSettings template) {
    addConfigurationElement(parentNode, template, CONFIGURATION);
  }

  private void addConfigurationElement(@NotNull Element parentNode, RunnerAndConfigurationSettings settings, String elementType) {
    Element configurationElement = new Element(elementType);
    parentNode.addContent(configurationElement);
    ((RunnerAndConfigurationSettingsImpl)settings).writeExternal(configurationElement);

    if (settings.getConfiguration() instanceof UnknownRunConfiguration) {
      return;
    }

    doWriteConfiguration(settings, configurationElement);
  }

  void doWriteConfiguration(@NotNull RunnerAndConfigurationSettings settings, @NotNull Element configurationElement) {
    List<BeforeRunTask> tasks = new ArrayList<>(getBeforeRunTasks(settings.getConfiguration()));
    Map<Key<BeforeRunTask>, BeforeRunTask> templateTasks = new THashMap<>();
    List<BeforeRunTask> beforeRunTasks = settings.isTemplate()
                                         ? getHardcodedBeforeRunTasks(settings.getConfiguration())
                                         : getBeforeRunTasks(getConfigurationTemplate(settings.getFactory()).getConfiguration());
    for (BeforeRunTask templateTask : beforeRunTasks) {
      templateTasks.put(templateTask.getProviderId(), templateTask);
      if (templateTask.isEnabled()) {
        boolean found = false;
        for (BeforeRunTask realTask : tasks) {
          if (realTask.getProviderId() == templateTask.getProviderId()) {
            found = true;
            break;
          }
        }
        if (!found) {
          BeforeRunTask clone = templateTask.clone();
          clone.setEnabled(false);
          tasks.add(0, clone);
        }
      }
    }

    // we have to always write empty method element otherwise no way to indicate that
    Element methodsElement = settings.isTemplate() ? new Element(METHOD) : null;
    for (int i = 0, size = tasks.size(); i < size; i++) {
      BeforeRunTask task = tasks.get(i);
      int j = 0;
      BeforeRunTask templateTask = null;
      for (Map.Entry<Key<BeforeRunTask>, BeforeRunTask> entry : templateTasks.entrySet()) {
        if (entry.getKey() == task.getProviderId()) {
          templateTask = entry.getValue();
          break;
        }
        j++;
      }
      if (task.equals(templateTask) && i == j) {
        // not necessary saving if the task is the same as template and on the same place
        continue;
      }
      Element child = new Element(OPTION);
      child.setAttribute(NAME_ATTR, task.getProviderId().toString());
      task.writeExternal(child);

      if (methodsElement == null) {
        methodsElement = new Element(METHOD);
      }
      methodsElement.addContent(child);
    }

    if (methodsElement != null) {
      configurationElement.addContent(methodsElement);
    }
  }

  @Override
  public void loadState(@NotNull Element parentNode) {
    myOrder.readExternal(parentNode);

    // migration (old ids to UUIDs)
    readList(myOrder);

    myRecentlyUsedTemporaries.clear();
    Element recentNode = parentNode.getChild(RECENT);
    if (recentNode != null) {
      JDOMExternalizableStringList list = new JDOMExternalizableStringList();
      list.readExternal(recentNode);
      readList(list);
      for (String name : list) {
        RunnerAndConfigurationSettings settings = myConfigurations.get(name);
        if (settings != null) {
          myRecentlyUsedTemporaries.add(settings.getConfiguration());
        }
      }
    }
    myOrdered = false;

    myLoadedSelectedConfigurationUniqueName = parentNode.getAttributeValue(SELECTED_ATTR);
    setSelectedConfigurationId(myLoadedSelectedConfigurationUniqueName);

    fireBeforeRunTasksUpdated();
    fireRunConfigurationSelected();
  }

  private void readList(@NotNull JDOMExternalizableStringList list) {
    for (int i = 0; i < list.size(); i++) {
      for (RunnerAndConfigurationSettings settings : myConfigurations.values()) {
        RunConfiguration configuration = settings.getConfiguration();
        //noinspection deprecation
        if (configuration != null && list.get(i).equals(configuration.getType().getDisplayName() + "." + configuration.getName() +
                                                        (configuration instanceof UnknownRunConfiguration ? configuration.getUniqueID() : ""))) {
          list.set(i, settings.getUniqueID());
          break;
        }
      }
    }
  }

  public void readContext(Element parentNode) throws InvalidDataException {
    myLoadedSelectedConfigurationUniqueName = parentNode.getAttributeValue(SELECTED_ATTR);

    for (Object aChildren : parentNode.getChildren()) {
      Element element = (Element)aChildren;
      RunnerAndConfigurationSettings config = loadConfiguration(element, false);
      if (myLoadedSelectedConfigurationUniqueName == null
          && config != null
          && Boolean.parseBoolean(element.getAttributeValue(SELECTED_ATTR))) {
        myLoadedSelectedConfigurationUniqueName = config.getUniqueID();
      }
    }

    setSelectedConfigurationId(myLoadedSelectedConfigurationUniqueName);

    fireRunConfigurationSelected();
  }

  @Nullable
  String findExistingConfigurationId(@Nullable RunnerAndConfigurationSettings settings) {
    if (settings != null) {
      for (Map.Entry<String, RunnerAndConfigurationSettings> entry : myConfigurations.entrySet()) {
        if (entry.getValue() == settings) {
          return entry.getKey();
        }
      }
    }
    return null;
  }

  // used by MPS, don't delete
  public void clearAll() {
    clear(true);
    myTypesByName.clear();
    initializeConfigurationTypes(new ConfigurationType[0]);
  }

  protected void clear(boolean allConfigurations) {
    List<RunnerAndConfigurationSettings> configurations;
    if (allConfigurations) {
      myConfigurations.clear();
      mySharedConfigurations.clear();
      myConfigurationToBeforeTasksMap.clear();
      mySelectedConfigurationId = null;
      configurations = new ArrayList<>(myConfigurations.values());
    }
    else {
      configurations = new SmartList<>();
      for (Iterator<RunnerAndConfigurationSettings> iterator = myConfigurations.values().iterator(); iterator.hasNext(); ) {
        RunnerAndConfigurationSettings configuration = iterator.next();
        if (configuration.isTemporary() || !isConfigurationShared(configuration)) {
          iterator.remove();

          mySharedConfigurations.remove(configuration.getUniqueID());
          myConfigurationToBeforeTasksMap.remove(configuration.getConfiguration());

          configurations.add(configuration);
        }
      }

      if (mySelectedConfigurationId != null && myConfigurations.containsKey(mySelectedConfigurationId)) {
        mySelectedConfigurationId = null;
      }
    }

    myUnknownElements = null;
    myTemplateConfigurationsMap.clear();
    myLoadedSelectedConfigurationUniqueName = null;
    myIconCache.clear();
    myRecentlyUsedTemporaries.clear();
    fireRunConfigurationsRemoved(configurations);
  }

  @Nullable
  public RunnerAndConfigurationSettings loadConfiguration(@NotNull Element element, boolean isShared) {
    RunnerAndConfigurationSettingsImpl settings = new RunnerAndConfigurationSettingsImpl(this);
    try {
      settings.readExternal(element);
    }
    catch (InvalidDataException e) {
      LOG.error(e);
    }

    ConfigurationFactory factory = settings.getFactory();
    if (factory == null) {
      return null;
    }

    doLoadConfiguration(element, isShared, settings, factory);
    return settings;
  }

  protected void doLoadConfiguration(@NotNull Element element,
                                     boolean isShared,
                                     RunnerAndConfigurationSettingsImpl settings,
                                     ConfigurationFactory factory) {
    List<BeforeRunTask> tasks = readStepsBeforeRun(element.getChild(METHOD), settings);
    if (settings.isTemplate()) {
      myTemplateConfigurationsMap.put(factory.getType().getId() + "." + factory.getName(), settings);
      setBeforeRunTasks(settings.getConfiguration(), tasks, true);
    }
    else {
      addConfiguration(settings, isShared, tasks, true);
      if (Boolean.parseBoolean(element.getAttributeValue(SELECTED_ATTR))) {
        // to support old style
        setSelectedConfiguration(settings);
      }
    }
  }

  @NotNull
  private List<BeforeRunTask> readStepsBeforeRun(@Nullable Element child, @NotNull RunnerAndConfigurationSettings settings) {
    List<BeforeRunTask> result = null;
    if (child != null) {
      for (Element methodElement : child.getChildren(OPTION)) {
        Key<? extends BeforeRunTask> id = getProviderKey(methodElement.getAttributeValue(NAME_ATTR));
        BeforeRunTask beforeRunTask = getProvider(id).createTask(settings.getConfiguration());
        if (beforeRunTask != null) {
          beforeRunTask.readExternal(methodElement);
          if (result == null) {
            result = new SmartList<>();
          }
          result.add(beforeRunTask);
        }
      }
    }
    return ContainerUtil.notNullize(result);
  }

  @Nullable
  public ConfigurationType getConfigurationType(final String typeName) {
    return myTypesByName.get(typeName);
  }

  @Nullable
  public ConfigurationFactory getFactory(final String typeName, String factoryName) {
    return getFactory(typeName, factoryName, false);
  }

  @Nullable
  public ConfigurationFactory getFactory(final String typeName, String factoryName, boolean checkUnknown) {
    final ConfigurationType type = myTypesByName.get(typeName);
    if (type == null && checkUnknown && typeName != null) {
      UnknownFeaturesCollector.getInstance(myProject).registerUnknownRunConfiguration(typeName);
    }
    if (factoryName == null) {
      factoryName = type != null ? type.getConfigurationFactories()[0].getName() : null;
    }
    return findFactoryOfTypeNameByName(typeName, factoryName);
  }


  @Nullable
  private ConfigurationFactory findFactoryOfTypeNameByName(final String typeName, final String factoryName) {
    ConfigurationType type = myTypesByName.get(typeName);
    if (type == null) {
      type = myTypesByName.get(UnknownConfigurationType.NAME);
    }

    return findFactoryOfTypeByName(type, factoryName);
  }

  @Nullable
  private static ConfigurationFactory findFactoryOfTypeByName(final ConfigurationType type, final String factoryName) {
    if (factoryName == null) return null;

    if (type instanceof UnknownConfigurationType) {
      return type.getConfigurationFactories()[0];
    }

    final ConfigurationFactory[] factories = type.getConfigurationFactories();
    for (final ConfigurationFactory factory : factories) {
      if (factoryName.equals(factory.getName())) return factory;
    }

    return null;
  }

  @Override
  @NotNull
  public String getComponentName() {
    return "RunManager";
  }

  @Override
  public void setTemporaryConfiguration(@Nullable final RunnerAndConfigurationSettings tempConfiguration) {
    if (tempConfiguration == null) return;

    tempConfiguration.setTemporary(true);

    addConfiguration(tempConfiguration, isConfigurationShared(tempConfiguration),
                     getBeforeRunTasks(tempConfiguration.getConfiguration()), false);
    if (Registry.is("select.run.configuration.from.context")) {
      setSelectedConfiguration(tempConfiguration);
    }
  }

  @NotNull
  Collection<RunnerAndConfigurationSettings> getStableConfigurations(boolean shared) {
    List<RunnerAndConfigurationSettings> result = null;
    for (RunnerAndConfigurationSettings configuration : myConfigurations.values()) {
      if (!configuration.isTemporary() && isConfigurationShared(configuration) == shared) {
        if (result == null) {
          result = new SmartList<>();
        }
        result.add(configuration);
      }
    }
    return ContainerUtil.notNullize(result);
  }

  @NotNull
  Collection<? extends RunnerAndConfigurationSettings> getConfigurationSettings() {
    return myConfigurations.values();
  }

  @Override
  public boolean isTemporary(@NotNull final RunConfiguration configuration) {
    return Arrays.asList(getTempConfigurations()).contains(configuration);
  }

  @Override
  @NotNull
  public List<RunnerAndConfigurationSettings> getTempConfigurationsList() {
    List<RunnerAndConfigurationSettings> configurations =
      ContainerUtil.filter(myConfigurations.values(), RunnerAndConfigurationSettings::isTemporary);
    return Collections.unmodifiableList(configurations);
  }

  @NotNull
  @Override
  public RunConfiguration[] getTempConfigurations() {
    List<RunnerAndConfigurationSettings> list = getTempConfigurationsList();
    RunConfiguration[] result = new RunConfiguration[list.size()];
    for (int i = 0; i < list.size(); i++) {
      result[i] = list.get(i).getConfiguration();
    }
    return result;
  }

  @Override
  public void makeStable(@NotNull RunnerAndConfigurationSettings settings) {
      settings.setTemporary(false);
      myRecentlyUsedTemporaries.remove(settings.getConfiguration());
      if (!myOrder.isEmpty()) {
        setOrdered(false);
      }
      fireRunConfigurationChanged(settings);
  }

  @Override
  public void makeStable(@NotNull RunConfiguration configuration) {
    RunnerAndConfigurationSettings settings = getSettings(configuration);
    if (settings != null) {
      makeStable(settings);
    }
  }

  @Override
  @NotNull
  public RunnerAndConfigurationSettings createRunConfiguration(@NotNull String name, @NotNull ConfigurationFactory factory) {
    return createConfiguration(name, factory);
  }

  @Override
  public boolean isConfigurationShared(final RunnerAndConfigurationSettings settings) {
    Boolean shared = mySharedConfigurations.get(settings.getUniqueID());
    if (shared == null) {
      final RunnerAndConfigurationSettings template = getConfigurationTemplate(settings.getFactory());
      shared = mySharedConfigurations.get(template.getUniqueID());
    }
    return shared != null && shared.booleanValue();
  }

  @Override
  @NotNull
  public <T extends BeforeRunTask> List<T> getBeforeRunTasks(Key<T> taskProviderID) {
    final List<T> tasks = new ArrayList<>();
    final List<RunnerAndConfigurationSettings> checkedTemplates = new ArrayList<>();
    List<RunnerAndConfigurationSettings> settingsList = new ArrayList<>(myConfigurations.values());
    for (RunnerAndConfigurationSettings settings : settingsList) {
      final List<BeforeRunTask> runTasks = getBeforeRunTasks(settings.getConfiguration());
      for (BeforeRunTask task : runTasks) {
        if (task != null && task.isEnabled() && task.getProviderId() == taskProviderID) {
          tasks.add((T)task);
        }
        else {
          final RunnerAndConfigurationSettings template = getConfigurationTemplate(settings.getFactory());
          if (!checkedTemplates.contains(template)) {
            checkedTemplates.add(template);
            final List<BeforeRunTask> templateTasks = getBeforeRunTasks(template.getConfiguration());
            for (BeforeRunTask templateTask : templateTasks) {
              if (templateTask != null && templateTask.isEnabled() && templateTask.getProviderId() == taskProviderID) {
                tasks.add((T)templateTask);
              }
            }
          }
        }
      }
    }
    return tasks;
  }

  @Override
  public Icon getConfigurationIcon(@NotNull final RunnerAndConfigurationSettings settings, boolean withLiveIndicator) {
    final String uniqueID = settings.getUniqueID();
    RunnerAndConfigurationSettings selectedConfiguration = getSelectedConfiguration();
    String selectedId = selectedConfiguration != null ? selectedConfiguration.getUniqueID() : "";
    if (selectedId.equals(uniqueID)) {
      myIconCache.checkValidity(uniqueID);
    }
    Icon icon = myIconCache.get(uniqueID, settings, myProject);
    if (withLiveIndicator) {
      List<RunContentDescriptor> runningDescriptors = ExecutionManagerImpl.getInstance(myProject).getRunningDescriptors(s -> s == settings);
      if (runningDescriptors.size() == 1) {
        icon = ExecutionUtil.getLiveIndicator(icon);
      }
      if (runningDescriptors.size() > 1) {
        icon = IconUtil.addText(icon, String.valueOf(runningDescriptors.size()));
      }
    }
    return icon;
  }

  public RunnerAndConfigurationSettings getConfigurationById(@NotNull final String id) {
    return myConfigurations.get(id);
  }

  @Override
  @Nullable
  public RunnerAndConfigurationSettings findConfigurationByName(@Nullable String name) {
    if (name == null) return null;
    for (RunnerAndConfigurationSettings each : myConfigurations.values()) {
      if (name.equals(each.getName())) return each;
    }
    return null;
  }

  @Nullable
  public RunnerAndConfigurationSettings findConfigurationByTypeAndName(@NotNull String typeId, @NotNull String name) {
    for (RunnerAndConfigurationSettings settings : getSortedConfigurations()) {
      ConfigurationType t = settings.getType();
      if (t != null && typeId.equals(t.getId()) && name.equals(settings.getName())) {
        return settings;
      }
    }
    return null;
  }

  @NotNull
  @Override
  public <T extends BeforeRunTask> List<T> getBeforeRunTasks(@NotNull RunConfiguration settings, Key<T> taskProviderID) {
    if (settings instanceof WrappingRunConfiguration) {
      return getBeforeRunTasks(((WrappingRunConfiguration)settings).getPeer(), taskProviderID);
    }
    List<BeforeRunTask> tasks = myConfigurationToBeforeTasksMap.get(settings);
    if (tasks == null) {
      tasks = getBeforeRunTasks(settings);
      myConfigurationToBeforeTasksMap.put(settings, tasks);
    }
    List<T> result = new SmartList<>();
    for (BeforeRunTask task : tasks) {
      if (task.getProviderId() == taskProviderID) {
        //noinspection unchecked
        result.add((T)task);
      }
    }
    return result;
  }

  @Override
  @NotNull
  public List<BeforeRunTask> getBeforeRunTasks(final RunConfiguration settings) {
    if (settings instanceof WrappingRunConfiguration) {
      return getBeforeRunTasks(((WrappingRunConfiguration)settings).getPeer());
    }

    List<BeforeRunTask> tasks = myConfigurationToBeforeTasksMap.get(settings);
    return tasks == null ? getTemplateBeforeRunTasks(settings) : getCopies(tasks);
  }

  private List<BeforeRunTask> getTemplateBeforeRunTasks(@NotNull RunConfiguration settings) {
    final RunnerAndConfigurationSettings template = getConfigurationTemplate(settings.getFactory());
    final List<BeforeRunTask> templateTasks = myConfigurationToBeforeTasksMap.get(template.getConfiguration());
    return templateTasks == null ? getHardcodedBeforeRunTasks(settings) : getCopies(templateTasks);
  }

  @NotNull
  private List<BeforeRunTask> getHardcodedBeforeRunTasks(@NotNull RunConfiguration settings) {
    List<BeforeRunTask> _tasks = new SmartList<>();
    for (BeforeRunTaskProvider<? extends BeforeRunTask> provider : Extensions
      .getExtensions(BeforeRunTaskProvider.EXTENSION_POINT_NAME, myProject)) {
      BeforeRunTask task = provider.createTask(settings);
      if (task != null && task.isEnabled()) {
        Key<? extends BeforeRunTask> providerID = provider.getId();
        settings.getFactory().configureBeforeRunTaskDefaults(providerID, task);
        if (task.isEnabled()) {
          _tasks.add(task);
        }
      }
    }
    return _tasks;
  }

  @NotNull
  private static List<BeforeRunTask> getCopies(@NotNull List<BeforeRunTask> original) {
    List<BeforeRunTask> result = new SmartList<>();
    for (BeforeRunTask task : original) {
      if (task.isEnabled()) {
        result.add(task.clone());
      }
    }
    return result;
  }

  public void shareConfiguration(final RunnerAndConfigurationSettings settings, final boolean shareConfiguration) {
    boolean shouldFire = settings != null && isConfigurationShared(settings) != shareConfiguration;
    if (shareConfiguration && settings.isTemporary()) {
      makeStable(settings);
    }
    mySharedConfigurations.put(settings.getUniqueID(), shareConfiguration);
    if (shouldFire) {
      fireRunConfigurationChanged(settings);
    }
  }

  @Override
  public final void setBeforeRunTasks(final RunConfiguration runConfiguration, @NotNull List<BeforeRunTask> tasks, boolean addEnabledTemplateTasksIfAbsent) {
    List<BeforeRunTask> result = new SmartList<>(tasks);
    if (addEnabledTemplateTasksIfAbsent) {
      List<BeforeRunTask> templates = getTemplateBeforeRunTasks(runConfiguration);
      Set<Key<BeforeRunTask>> idsToSet = new THashSet<>();
      for (BeforeRunTask task : tasks) {
        idsToSet.add(task.getProviderId());
      }
      int i = 0;
      for (BeforeRunTask template : templates) {
        if (!idsToSet.contains(template.getProviderId())) {
          result.add(i, template);
          i++;
        }
      }
    }
    myConfigurationToBeforeTasksMap.put(runConfiguration, result);
    fireBeforeRunTasksUpdated();
  }

  @Override
  public void addConfiguration(@NotNull RunnerAndConfigurationSettings settings, boolean isShared) {
    addConfiguration(settings, isShared, getTemplateBeforeRunTasks(settings.getConfiguration()), false);
  }

  void removeNotExistingSharedConfigurations(@NotNull Set<String> existing) {
    List<RunnerAndConfigurationSettings> removed = null;
    for (Iterator<Map.Entry<String, RunnerAndConfigurationSettings>> it = myConfigurations.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<String, RunnerAndConfigurationSettings> entry = it.next();
      final RunnerAndConfigurationSettings settings = entry.getValue();
      if (!settings.isTemplate() && isConfigurationShared(settings) && !existing.contains(settings.getUniqueID())) {
        if (removed == null) {
          removed = new SmartList<>();
        }
        removed.add(settings);
        it.remove();
      }
    }
    fireRunConfigurationsRemoved(removed);
  }

  public void fireBeginUpdate() {
    myDispatcher.getMulticaster().beginUpdate();
  }

  public void fireEndUpdate() {
    myDispatcher.getMulticaster().endUpdate();
  }
  
  public void fireRunConfigurationChanged(@NotNull RunnerAndConfigurationSettings settings) {
    myDispatcher.getMulticaster().runConfigurationChanged(settings, null);
  }

  private void fireRunConfigurationsRemoved(@Nullable List<RunnerAndConfigurationSettings> removed) {
    if (!ContainerUtil.isEmpty(removed)) {
      myRecentlyUsedTemporaries.removeAll(removed);
      for (RunnerAndConfigurationSettings settings : removed) {
        myDispatcher.getMulticaster().runConfigurationRemoved(settings);
      }
    }
  }

  private void fireRunConfigurationSelected() {
    myDispatcher.getMulticaster().runConfigurationSelected();
  }

  @Override
  public void addRunManagerListener(RunManagerListener listener) {
    myDispatcher.addListener(listener);
  }

  @Override
  public void removeRunManagerListener(RunManagerListener listener) {
    myDispatcher.removeListener(listener);
  }

  public void fireBeforeRunTasksUpdated() {
    myDispatcher.getMulticaster().beforeRunTasksChanged();
  }

  private Map<Key<? extends BeforeRunTask>, BeforeRunTaskProvider> myBeforeStepsMap;
  private Map<String, Key<? extends BeforeRunTask>> myProviderKeysMap;

  @NotNull
  private synchronized BeforeRunTaskProvider getProvider(Key<? extends BeforeRunTask> providerId) {
    if (myBeforeStepsMap == null) {
      initProviderMaps();
    }
    return myBeforeStepsMap.get(providerId);
  }

  @NotNull
  private synchronized Key<? extends BeforeRunTask> getProviderKey(String keyString) {
    if (myProviderKeysMap == null) {
      initProviderMaps();
    }
    Key<? extends BeforeRunTask> id = myProviderKeysMap.get(keyString);
    if (id == null) {
      final UnknownBeforeRunTaskProvider provider = new UnknownBeforeRunTaskProvider(keyString);
      id = provider.getId();
      myProviderKeysMap.put(keyString, id);
      myBeforeStepsMap.put(id, provider);
    }
    return id;
  }

  private void initProviderMaps() {
    myBeforeStepsMap = new LinkedHashMap<>();
    myProviderKeysMap = new LinkedHashMap<>();
    for (BeforeRunTaskProvider<? extends BeforeRunTask> provider : Extensions
      .getExtensions(BeforeRunTaskProvider.EXTENSION_POINT_NAME, myProject)) {
      final Key<? extends BeforeRunTask> id = provider.getId();
      myBeforeStepsMap.put(id, provider);
      myProviderKeysMap.put(id.toString(), id);
    }
  }
}
