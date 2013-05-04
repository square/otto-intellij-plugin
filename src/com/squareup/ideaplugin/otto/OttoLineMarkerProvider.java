package com.squareup.ideaplugin.otto;

import com.intellij.codeHighlighting.Pass;
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParameterList;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
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

  public static final Decider INSTANTIATIONS_OR_PRODUCERS = new Decider() {
    @Override public boolean shouldShow(Usage usage) {
      return INSTANTIATIONS.shouldShow(usage) || PRODUCERS.shouldShow(usage);
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

  public static final GutterIconNavigationHandler<PsiNewExpression> SHOW_SUBSCRIBERS =
      new GutterIconNavigationHandler<PsiNewExpression>() {
        @Override public void navigate(MouseEvent e, PsiNewExpression eventPost) {
          PsiClass eventClass = ((PsiClassType) eventPost.getType()).resolve();
          System.out.println("looking for eventClass = " + eventClass);
          new ShowUsagesAction(SUBSCRIBERS).startFindUsages(eventClass, new RelativePoint(e),
              PsiUtilBase.findEditor(eventPost), MAX_USAGES);
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
        @Override public void navigate(MouseEvent mouseEvent, PsiElement subscribeMethod) {
          PsiTypeElement parameterTypeElement = getMethodParameter((PsiMethod) subscribeMethod);

          if (parameterTypeElement.getType() instanceof PsiClassType) {
            System.out.println("looking for parameterTypeElement = " + parameterTypeElement);
            PsiClass eventClass = ((PsiClassType) parameterTypeElement.getType()).resolve();
            new ShowUsagesAction(INSTANTIATIONS_OR_PRODUCERS).startFindUsages(eventClass,
                new RelativePoint(mouseEvent), PsiUtilBase.findEditor(parameterTypeElement),
                MAX_USAGES);
          }
        }
      };

  private static final GutterIconNavigationHandler<PsiElement> SHOW_SUBSCRIBERS_FROM_PRODUCERS =
      new GutterIconNavigationHandler<PsiElement>() {
        @Override public void navigate(MouseEvent mouseEvent, PsiElement psiElement) {
          if (psiElement instanceof PsiMethod) {
            PsiMethod psiMethod = (PsiMethod) psiElement;
            PsiTypeElement returnTypeElement = psiMethod.getReturnTypeElement();
            System.out.println("looking for returnTypeElement = " + returnTypeElement);
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
    } else if (element instanceof PsiNewExpression) {
      PsiNewExpression psiNewExpression = (PsiNewExpression) element;
      PsiType psiType = psiNewExpression.getType();
      if (psiType != null) {
        String className = psiType.getCanonicalText();
        OttoProjectHandler ottoProjectHandler = OttoProjectHandler.get(element.getProject());

        // Don't show event instantiations inside producers
        PsiMethod method = PsiConsultantImpl.findMethod(element);
        if (method != null && PsiConsultantImpl.hasAnnotation(method,
            OttoProjectHandler.PRODUCER_CLASS_NAME)) {
          return null;
        }

        if (ottoProjectHandler.isEventClass(className)) {
          return new LineMarkerInfo<PsiNewExpression>(psiNewExpression, element.getTextRange(),
              ICON, Pass.UPDATE_ALL, null, SHOW_SUBSCRIBERS, GutterIconRenderer.Alignment.LEFT);
        }
      }
    }

    return null;
  }

  public static @Nullable PsiTypeElement getMethodParameter(PsiMethod subscribeMethod) {
    PsiParameterList parameterList = subscribeMethod.getParameterList();
    if (parameterList.getParametersCount() != 1) {
      System.out.println("DEBUG: wha?");
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
