package com.squareup.ideaplugin.otto;

import com.intellij.codeHighlighting.Pass;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParameterList;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiUtilBase;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageInfo2UsageAdapter;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.List;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class OttoLineMarkerProvider implements LineMarkerProvider {
  public static final Icon ICON = IconLoader.getIcon("/icons/otto.png");

  public static final Decider INSTANTIATIONS = new Decider() {
    @Override public boolean shouldShow(Usage usage) {
      PsiElement element = ((UsageInfo2UsageAdapter) usage).getElement();
      if (element.getParent() instanceof PsiNewExpression) {
        PsiMethod method = PsiConsultantImpl.findMethod(element);
        return !PsiConsultantImpl.hasAnnotation(method, OttoProjectHandler.PRODUCER_CLASS_NAME);
      }
      return false;
    }
  };

  public static final Decider PRODUCERS = new Decider() {
    @Override public boolean shouldShow(Usage usage) {
      PsiElement element = ((UsageInfo2UsageAdapter) usage).getElement().getContext();
      if (element != null) {
        PsiElement parent = element.getParent();
        if (parent != null && parent instanceof PsiMethod) {
          return PsiConsultantImpl.findAnnotationOnMethod((PsiMethod) parent,
              OttoProjectHandler.PRODUCER_CLASS_NAME) != null;
        }
      }
      return false;
    }
  };

  public static final Decider SUBSCRIBERS = new Decider() {
    @Override public boolean shouldShow(Usage usage) {
      PsiElement element = ((UsageInfo2UsageAdapter) usage).getElement();
      if (element instanceof PsiJavaCodeReferenceElement) {
        if ((element = element.getContext()) instanceof PsiTypeElement) {
          if ((element = element.getContext()) instanceof PsiParameter) {
            if ((element = element.getContext()) instanceof PsiParameterList) {
              if ((element = element.getContext()) instanceof PsiMethod) {
                PsiAnnotation subscriberAnnotation =
                    PsiConsultantImpl.findAnnotationOnMethod((PsiMethod) element,
                        OttoProjectHandler.SUBSCRIBE_CLASS_NAME);
                return subscriberAnnotation != null;
              }
            }
          }
        }
      }
      return false;
    }
  };
  public static final Decider ALL = new Decider() {
    @Override public boolean shouldShow(Usage usage) {
      return INSTANTIATIONS.shouldShow(usage)
          || SUBSCRIBERS.shouldShow(usage)
          || PRODUCERS.shouldShow(usage);
    }
  };

  public static final int MAX_USAGES = 100;

  public static final GutterIconNavigationHandler<PsiElement> SHOW_SUBSCRIBERS =
      new GutterIconNavigationHandler<PsiElement>() {
        @Override public void navigate(MouseEvent e, PsiElement psiElement) {
          if (psiElement instanceof PsiClass) {
            PsiClass eventClass = (PsiClass) psiElement;
            new ShowUsagesAction(SUBSCRIBERS).startFindUsages(eventClass, new RelativePoint(e),
                PsiUtilBase.findEditor(eventClass), MAX_USAGES);
          }
        }
      };

  public static final GutterIconNavigationHandler<PsiClass> SHOW_ALL =
      new GutterIconNavigationHandler<PsiClass>() {
        @Override public void navigate(MouseEvent e, PsiClass psiClass) {
          new ShowUsagesAction(ALL).startFindUsages(psiClass,
              new RelativePoint(e), PsiUtilBase.findEditor(psiClass), MAX_USAGES);
        }
      };

  public static final GutterIconNavigationHandler<PsiElement> SHOW_INSTANTIATIONS_AND_PRODUCERS =
      new GutterIconNavigationHandler<PsiElement>() {
        @Override public void navigate(final MouseEvent mouseEvent, final PsiElement psiElement) {
          final PsiTypeElement parameterTypeElement = getMethodParameter((PsiMethod) psiElement);
          if (parameterTypeElement.getType() instanceof PsiClassType) {
            final PsiClass eventClass = ((PsiClassType) parameterTypeElement.getType()).resolve();
            PickAction.startPicker(new RelativePoint(mouseEvent), new PickAction.Callback() {
              @Override public void onTypeChose(PickAction.Type type) {
                if (type.equals(PickAction.Type.PRODUCER)) {
                  new ShowUsagesAction(PRODUCERS).startFindUsages(eventClass,
                      new RelativePoint(mouseEvent), PsiUtilBase.findEditor(psiElement),
                      MAX_USAGES);
                } else if (type.equals(PickAction.Type.EVENT_POST)) {
                  PsiMethod ottoBusMethod = getOttoBusMethod(psiElement);
                  new ShowUsagesAction(new BusPostDecider(eventClass)).startFindUsages(
                      ottoBusMethod, new RelativePoint(mouseEvent),
                      PsiUtilBase.findEditor(psiElement), MAX_USAGES);
                }
              }
            });
          }
        }
      };

  private static PsiMethod getOttoBusMethod(PsiElement subscribeMethod) {
    Project project = subscribeMethod.getProject();
    JavaPsiFacade javaPsiFacade = JavaPsiFacade.getInstance(project);
    GlobalSearchScope globalSearchScope = GlobalSearchScope.allScope(project);

    PsiClass busClass =
        javaPsiFacade.findClass(OttoProjectHandler.BUS_CLASS_NAME, globalSearchScope);
    if (busClass != null) {
      for (PsiMethod psiMethod : busClass.getMethods()) {
        if (psiMethod.getName().equals("post")) {
          return psiMethod;
        }
      }
    }
    return null;
  }

  private static final GutterIconNavigationHandler<PsiElement> SHOW_SUBSCRIBERS_FROM_PRODUCERS =
      new GutterIconNavigationHandler<PsiElement>() {
        @Override public void navigate(MouseEvent mouseEvent, PsiElement psiElement) {
          if (psiElement instanceof PsiMethod) {
            PsiMethod psiMethod = (PsiMethod) psiElement;
            PsiTypeElement returnTypeElement = psiMethod.getReturnTypeElement();
            //System.out.println("looking for returnTypeElement = " + returnTypeElement);
            PsiClass eventClass = ((PsiClassType) returnTypeElement.getType()).resolve();

            new ShowUsagesAction(SUBSCRIBERS).startFindUsages(eventClass,
                new RelativePoint(mouseEvent), PsiUtilBase.findEditor(returnTypeElement),
                MAX_USAGES);
          }
        }
      };

  @Nullable @Override public LineMarkerInfo getLineMarkerInfo(@NotNull final PsiElement element) {
    if (element instanceof PsiMethod) {
      PsiMethod psiMethod = (PsiMethod) element;
      PsiAnnotation subscriberAnnotation = PsiConsultantImpl.findAnnotationOnMethod(psiMethod,
          OttoProjectHandler.SUBSCRIBE_CLASS_NAME);
      if (subscriberAnnotation != null) {
        PsiTypeElement methodParameter = getMethodParameter(psiMethod);
        if (methodParameter != null) {
          return new LineMarkerInfo<PsiElement>(psiMethod, methodParameter.getTextRange(), ICON,
              Pass.UPDATE_ALL, null, SHOW_INSTANTIATIONS_AND_PRODUCERS,
              GutterIconRenderer.Alignment.LEFT);
        }
      }

      PsiAnnotation producerAnnotation = PsiConsultantImpl.findAnnotationOnMethod(psiMethod,
          OttoProjectHandler.PRODUCER_CLASS_NAME);
      if (producerAnnotation != null) {
        return new LineMarkerInfo<PsiElement>(psiMethod, psiMethod.getTextRange(), ICON,
            Pass.UPDATE_ALL, null, SHOW_SUBSCRIBERS_FROM_PRODUCERS,
            GutterIconRenderer.Alignment.LEFT);
      }
    } else if (element instanceof PsiClass) {
      PsiClass psiClass = (PsiClass) element;
      String qualifiedName = psiClass.getQualifiedName();
      if (qualifiedName != null) {
        OttoProjectHandler ottoProjectHandler = OttoProjectHandler.get(element.getProject());
        if (ottoProjectHandler.isEventClass(qualifiedName)) {
          PsiIdentifier nameIdentifier = psiClass.getNameIdentifier();
          if (nameIdentifier != null) {
            return new LineMarkerInfo<PsiClass>(psiClass, nameIdentifier.getTextRange(), ICON,
                Pass.UPDATE_ALL, null, SHOW_ALL, GutterIconRenderer.Alignment.LEFT);
          }
        }
      }
    } else if (element instanceof PsiMethodCallExpression) {
      PsiMethodCallExpression expression = (PsiMethodCallExpression) element;
      PsiMethod psiMethod = expression.resolveMethod();
      if (psiMethod != null) {
        if (psiMethod.equals(getOttoBusMethod(element))) {
          PsiType[] expressionTypes = expression.getArgumentList().getExpressionTypes();
          if (expressionTypes.length > 0) {
            return new LineMarkerInfo<PsiElement>(element, element.getTextRange(), ICON,
                Pass.UPDATE_ALL, null, SHOW_SUBSCRIBERS, GutterIconRenderer.Alignment.LEFT);
          }

        }
      }
    }

    return null;
  }

  public static @Nullable PsiTypeElement getMethodParameter(PsiMethod subscribeMethod) {
    PsiParameterList parameterList = subscribeMethod.getParameterList();
    if (parameterList.getParametersCount() != 1) {
      return null;
    } else {
      PsiParameter subscribeMethodParam = parameterList.getParameters()[0];
      return subscribeMethodParam.getTypeElement();
    }
  }

  @Override public void collectSlowLineMarkers(@NotNull List<PsiElement> psiElements,
      @NotNull Collection<LineMarkerInfo> lineMarkerInfos) {
  }
}
