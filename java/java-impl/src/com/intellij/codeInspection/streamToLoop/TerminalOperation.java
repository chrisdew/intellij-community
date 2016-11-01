/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.intellij.codeInspection.streamToLoop;

import com.intellij.codeInspection.streamToLoop.StreamToLoopInspection.StreamToLoopReplacementContext;
import com.intellij.psi.*;
import com.intellij.psi.util.TypeConversionUtil;
import com.siyeh.ig.psiutils.BoolUtils;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * @author Tagir Valeev
 */
abstract class TerminalOperation extends Operation {
  @Override
  final String wrap(StreamVariable inVar, StreamVariable outVar, String code, StreamToLoopReplacementContext context) {
    return generate(inVar, context);
  }

  @Override
  final void rename(String oldName, String newName, StreamToLoopReplacementContext context) {
    throw new IllegalStateException("Should not be called for terminal operation (tried to rename " + oldName + " -> " + newName + ")");
  }

  @Override
  final boolean changesVariable() {
    return true;
  }

  abstract String generate(StreamVariable inVar, StreamToLoopReplacementContext context);

  @NotNull
  @Contract(pure = true)
  private static String getOptionalClass(String type) {
    switch (type) {
      case "int":
        return "java.util.OptionalInt";
      case "long":
        return "java.util.OptionalLong";
      case "double":
        return "java.util.OptionalDouble";
      default:
        return "java.util.Optional";
    }
  }

  @Nullable
  static TerminalOperation createTerminal(String name, PsiExpression[] args, PsiType callType, String className, boolean isVoid) {
    if(isVoid) {
      if ((name.equals("forEach") || name.equals("forEachOrdered")) && args.length == 1) {
        FunctionHelper fn = FunctionHelper.create(args[0], 1, true);
        return fn == null ? null : new ForEachTerminalOperation(fn);
      }
      return null;
    }
    if(name.equals("count") && args.length == 0) {
      return new AccumulatedTerminalOperation("count", "long", "0", "{acc}++;");
    }
    if(name.equals("sum") && args.length == 0) {
      return new AccumulatedTerminalOperation("sum", callType.getCanonicalText(), "0", "{acc}+={item};");
    }
    if(name.equals("average") && args.length == 0) {
      if(className.equals(CommonClassNames.JAVA_UTIL_STREAM_DOUBLE_STREAM)) {
        return new AverageTerminalOperation(true);
      }
      else if(className.equals(CommonClassNames.JAVA_UTIL_STREAM_INT_STREAM) ||
              className.equals(CommonClassNames.JAVA_UTIL_STREAM_LONG_STREAM)) {
        return new AverageTerminalOperation(false);
      }
    }
    if(name.equals("summaryStatistics") && args.length == 0) {
      return new AccumulatedTerminalOperation("stat", callType.getCanonicalText(), "new " + callType.getCanonicalText() + "()",
                                              "{acc}.accept({item});");
    }
    if((name.equals("findFirst") || name.equals("findAny")) && args.length == 0) {
      return new FindTerminalOperation(callType.getCanonicalText());
    }
    if((name.equals("anyMatch") || name.equals("allMatch") || name.equals("noneMatch")) && args.length == 1) {
      FunctionHelper fn = FunctionHelper.create(args[0], 1, true);
      return fn == null ? null : new MatchTerminalOperation(fn, name);
    }
    if(name.equals("reduce")) {
      if(args.length == 2 || args.length == 3) {
        FunctionHelper fn = FunctionHelper.create(args[1], 2, true);
        if(fn != null) {
          return new ReduceTerminalOperation(args[0], fn, callType.getCanonicalText());
        }
      }
      if(args.length == 1) {
        PsiType optionalElementType = getOptionalElementType(callType);
        FunctionHelper fn = FunctionHelper.create(args[0], 2, true);
        if(fn != null && optionalElementType != null) {
          return new ReduceToOptionalTerminalOperation(fn, optionalElementType.getCanonicalText());
        }
      }
    }
    if(name.equals("toArray") && args.length < 2) {
      if(!(callType instanceof PsiArrayType)) return null;
      PsiType componentType = ((PsiArrayType)callType).getComponentType();
      if (componentType instanceof PsiPrimitiveType) {
        if(args.length == 0) return new ToPrimitiveArrayTerminalOperation(componentType.getCanonicalText());
      }
      else {
        String arr = "";
        if(args.length == 1) {
          if(!(args[0] instanceof PsiMethodReferenceExpression)) return null;
          PsiMethodReferenceExpression arrCtor = (PsiMethodReferenceExpression)args[0];
          if(!arrCtor.isConstructor()) return null;
          PsiTypeElement typeElement = arrCtor.getQualifierType();
          if(typeElement == null) return null;
          PsiType type = typeElement.getType();
          if(!(type instanceof PsiArrayType)) return null;
          arr = "new "+type.getCanonicalText().replaceFirst("\\[]", "[0]");
        }
        return new AccumulatedTerminalOperation("list", CommonClassNames.JAVA_UTIL_LIST + "<" + componentType.getCanonicalText() + ">",
                                                "new "+ CommonClassNames.JAVA_UTIL_ARRAY_LIST+"<>()", "{acc}.add({item});",
                                                "{acc}.toArray("+arr+")");
      }
    }
    if(name.equals("collect") && args.length == 1) {
      if(args[0] instanceof PsiMethodCallExpression) {
        PsiMethodCallExpression collectorCall = (PsiMethodCallExpression)args[0];
        PsiExpression[] collectorArgs = collectorCall.getArgumentList().getExpressions();
        PsiMethod collector = collectorCall.resolveMethod();
        if(collector == null) return null;
        PsiClass collectorClass = collector.getContainingClass();
        if(collectorClass != null && CommonClassNames.JAVA_UTIL_STREAM_COLLECTORS.equals(collectorClass.getQualifiedName())) {
          if(collector.getName().equals("toList") && collectorArgs.length == 0) {
            return AccumulatedTerminalOperation.toCollection(callType, CommonClassNames.JAVA_UTIL_ARRAY_LIST, "list");
          }
          if(collector.getName().equals("toSet") && collectorArgs.length == 0) {
            return AccumulatedTerminalOperation.toCollection(callType, CommonClassNames.JAVA_UTIL_HASH_SET, "set");
          }
          if(collector.getName().equals("toCollection") && collectorArgs.length == 1) {
            FunctionHelper fn = FunctionHelper.create(collectorArgs[0], 0, true);
            if(fn != null) {
              return new ToCollectionTerminalOperation(fn, callType);
            }
          }
          if(collector.getName().equals("reducing") && collectorArgs.length == 2) {
            FunctionHelper fn = FunctionHelper.create(collectorArgs[1], 2, true);
            if(fn != null) {
              return new ReduceTerminalOperation(collectorArgs[0], fn, callType.getCanonicalText());
            }
          }
          if(collector.getName().equals("reducing") && collectorArgs.length == 1) {
            PsiType optionalElementType = getOptionalElementType(callType);
            FunctionHelper fn = FunctionHelper.create(collectorArgs[0], 2, true);
            if(fn != null && optionalElementType != null) {
              return new ReduceToOptionalTerminalOperation(fn, optionalElementType.getCanonicalText());
            }
          }
          if(collector.getName().equals("joining")) {
            if(collectorArgs.length == 0) {
              return new AccumulatedTerminalOperation("sb", CommonClassNames.JAVA_LANG_STRING_BUILDER,
                                                      "new " + CommonClassNames.JAVA_LANG_STRING_BUILDER + "()", "{acc}.append({item});",
                                                      "{acc}.toString()");
            }
            if(collectorArgs.length == 1 || collectorArgs.length == 3) {
              String initializer = "new java.util.StringJoiner(" + StreamEx.of(collectorArgs).map(PsiElement::getText).joining(",") + ")";
              return new AccumulatedTerminalOperation("joiner", "java.util.StringJoiner", initializer,
                                                      "{acc}.add({item});", "{acc}.toString()");
            }
          }
        }
      }
    }
    return null;
  }

  @Contract("null -> null")
  static PsiType getOptionalElementType(PsiType type) {
    if(!(type instanceof PsiClassType)) return null;
    PsiClass aClass = ((PsiClassType)type).resolve();
    if(aClass == null) return null;
    if("java.util.OptionalInt".equals(aClass.getQualifiedName())) {
      return PsiType.INT;
    }
    if("java.util.OptionalLong".equals(aClass.getQualifiedName())) {
      return PsiType.LONG;
    }
    if("java.util.OptionalDouble".equals(aClass.getQualifiedName())) {
      return PsiType.DOUBLE;
    }
    if(!CommonClassNames.JAVA_UTIL_OPTIONAL.equals(aClass.getQualifiedName())) return null;
    PsiType[] parameters = ((PsiClassType)type).getParameters();
    if(parameters.length != 1) return null;
    PsiType streamType = parameters[0];
    if(streamType instanceof PsiCapturedWildcardType) {
      streamType = ((PsiCapturedWildcardType)streamType).getUpperBound();
    }
    return streamType;
  }

  static class ReduceTerminalOperation extends TerminalOperation {
    private PsiExpression myIdentity;
    private String myType;
    private FunctionHelper myUpdater;

    public ReduceTerminalOperation(PsiExpression identity, FunctionHelper updater, String type) {
      myIdentity = identity;
      myType = type;
      myUpdater = updater;
    }

    @Override
    void registerUsedNames(Consumer<String> usedNameConsumer) {
      FunctionHelper.processUsedNames(myIdentity, usedNameConsumer);
      myUpdater.registerUsedNames(usedNameConsumer);
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      String accumulator = context.declareResult("acc", myType, myIdentity.getText());
      myUpdater.transform(context, accumulator, inVar.getName());
      return accumulator + "=" + myUpdater.getText() + ";";
    }
  }

  static class ReduceToOptionalTerminalOperation extends TerminalOperation {
    private String myType;
    private FunctionHelper myUpdater;

    public ReduceToOptionalTerminalOperation(FunctionHelper updater, String type) {
      myType = type;
      myUpdater = updater;
    }

    @Override
    void registerUsedNames(Consumer<String> usedNameConsumer) {
      myUpdater.registerUsedNames(usedNameConsumer);
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      String seen = context.declare("seen", "boolean", "false");
      String accumulator = context.declareResult("acc", myType, TypeConversionUtil.isPrimitive(myType) ? "0" : "null");
      myUpdater.transform(context, accumulator, inVar.getName());
      String optionalClass = getOptionalClass(myType);
      context.setFinisher("(" + seen + "?" + optionalClass + ".of(" + accumulator + "):" + optionalClass + ".empty())");
      return "if(!" + seen + ") {\n" +
             seen + "=true;\n" +
             accumulator + "=" + inVar + ";\n" +
             "} else {\n" +
             accumulator + "=" + myUpdater.getText() + ";\n" +
             "}\n";
    }
  }

  static class AverageTerminalOperation extends TerminalOperation {
    private boolean myDoubleAccumulator;

    public AverageTerminalOperation(boolean doubleAccumulator) {
      myDoubleAccumulator = doubleAccumulator;
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      String sum = context.declareResult("sum", myDoubleAccumulator ? "double" : "long", "0");
      String count = context.declare("count", "long", "0");
      context.setFinisher("("+count+"==0?java.util.OptionalDouble.empty():"
                          +"java.util.OptionalDouble.of("+(myDoubleAccumulator?"":"(double)")+sum+"/"+count+"))");
      return sum + "+=" + inVar + ";\n" + count + "++;\n";
    }
  }

  static class ToPrimitiveArrayTerminalOperation extends TerminalOperation {
    private String myType;

    ToPrimitiveArrayTerminalOperation(String type) {
      myType = type;
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      String arr = context.declareResult("arr", myType + "[]", "new " + myType + "[10]");
      String count = context.declare("count", "int", "0");
      context.setFinisher("java.util.Arrays.copyOfRange("+arr+",0,"+count+")");
      return "if(" + arr + ".length==" + count + ") " + arr + "=java.util.Arrays.copyOf(" + arr + "," + count + "*2);\n" +
             arr + "[" + count + "++]=" + inVar + ";\n";
    }
  }

  static class FindTerminalOperation extends TerminalOperation {
    private String myType;

    public FindTerminalOperation(String type) {
      myType = type;
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      int pos = myType.indexOf('<');
      String optType = pos == -1 ? myType : myType.substring(0, pos);
      return context.assignAndBreak("found", myType, optType + ".of(" + inVar + ")", optType + ".empty()");
    }
  }

  static class MatchTerminalOperation extends TerminalOperation {
    private final FunctionHelper myFn;
    private final String myName;
    private final boolean myDefaultValue, myNegatePredicate;

    public MatchTerminalOperation(FunctionHelper fn, String name) {
      myFn = fn;
      switch(name) {
        case "anyMatch":
          myName = "found";
          myDefaultValue = false;
          myNegatePredicate = false;
          break;
        case "allMatch":
          myName = "allMatch";
          myDefaultValue = true;
          myNegatePredicate = true;
          break;
        case "noneMatch":
          myName = "noneMatch";
          myDefaultValue = true;
          myNegatePredicate = false;
          break;
        default:
          throw new IllegalArgumentException(name);
      }
    }

    @Override
    void registerUsedNames(Consumer<String> usedNameConsumer) {
      myFn.registerUsedNames(usedNameConsumer);
    }

    @Override
    public void suggestNames(StreamVariable inVar, StreamVariable outVar) {
      myFn.suggestVariableName(inVar, 0);
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      myFn.transform(context, inVar.getName());
      String expression = myNegatePredicate ? BoolUtils.getNegatedExpressionText(myFn.getExpression()) : myFn.getText();
      return "if(" + expression + ") {\n" +
             context.assignAndBreak(myName, PsiType.BOOLEAN.getCanonicalText(), String.valueOf(!myDefaultValue), String.valueOf(myDefaultValue)) +
             "}\n";
    }
  }

  static class AccumulatedTerminalOperation extends TerminalOperation {
    private String myAccName;
    private String myAccType;
    private String myAccInitializer;
    private String myUpdateTemplate;
    private String myFinisherTemplate;

    /**
     * @param accName desired name for accumulator variable
     * @param accType type of accumulator variable
     * @param accInitializer initializer for accumulator variable
     * @param updateTemplate template to update accumulator. May contain {@code {acc}} - reference to accumulator variable
     *                       and {@code {item}} - reference to stream element.
     * @param finisherTemplate template to final result. May contain {@code {acc}} - reference to accumulator variable.
     *                         By default it's {@code "{acc}"}
     */
    AccumulatedTerminalOperation(String accName, String accType, String accInitializer, String updateTemplate, String finisherTemplate) {
      myAccName = accName;
      myAccType = accType;
      myAccInitializer = accInitializer;
      myUpdateTemplate = updateTemplate;
      myFinisherTemplate = finisherTemplate;
    }

    AccumulatedTerminalOperation(String accName, String accType, String accInitializer, String updateTemplate) {
      this(accName, accType, accInitializer, updateTemplate, "{acc}");
    }

    @Override
    public String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      String varName = context.declareResult(myAccName, myAccType, myAccInitializer);
      context.setFinisher(myFinisherTemplate.replace("{acc}", varName));
      return myUpdateTemplate.replace("{item}", inVar.getName()).replace("{acc}", varName);
    }

    public static AccumulatedTerminalOperation toCollection(PsiType collectionType, String implementationType, String varName) {
      return new AccumulatedTerminalOperation(varName, collectionType.getCanonicalText(), "new " + implementationType + "<>()",
                                              "{acc}.add({item});");
    }
  }

  static class ToCollectionTerminalOperation extends TerminalOperation {
    private final String myType;
    private final FunctionHelper myFn;

    public ToCollectionTerminalOperation(FunctionHelper fn, PsiType callType) {
      myFn = fn;
      myType = callType.getCanonicalText();
    }

    @Override
    void registerUsedNames(Consumer<String> usedNameConsumer) {
      myFn.registerUsedNames(usedNameConsumer);
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      // TODO: remove redundant type arguments
      myFn.transform(context);
      String collection = context.declareResult("collection", myType, myFn.getText());
      return collection+".add("+inVar+");\n";
    }
  }

  static class ForEachTerminalOperation extends TerminalOperation {
    private FunctionHelper myFn;

    public ForEachTerminalOperation(FunctionHelper fn) {
      myFn = fn;
    }

    @Override
    public void suggestNames(StreamVariable inVar, StreamVariable outVar) {
      myFn.suggestVariableName(inVar, 0);
    }

    @Override
    void registerUsedNames(Consumer<String> usedNameConsumer) {
      myFn.registerUsedNames(usedNameConsumer);
    }

    @Override
    String generate(StreamVariable inVar, StreamToLoopReplacementContext context) {
      myFn.transform(context, inVar.getName());
      return myFn.getText()+";\n";
    }
  }
}
