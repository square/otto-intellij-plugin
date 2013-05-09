package com.squareup.ideaplugin.otto;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiType;

public class PsiConsultantImpl {

  static PsiMethodCallExpression findMethodCall(PsiElement element) {
    if (element == null) {
      return null;
    }
    else if (element instanceof PsiMethodCallExpression) {
      return (PsiMethodCallExpression) element;
    } else {
      return findMethodCall(element.getParent());
    }
  }

  static PsiAnnotation findAnnotationOnMethod(PsiMethod psiMethod, String annotationName) {
    PsiModifierList modifierList = psiMethod.getModifierList();
    for (PsiAnnotation psiAnnotation : modifierList.getAnnotations()) {
      if (annotationName.equals(psiAnnotation.getQualifiedName())) {
        return psiAnnotation;
      }
    }
    return null;
  }

  static PsiClass getClass(PsiType psiType) {
    if (psiType instanceof PsiClassType) {
      return ((PsiClassType) psiType).resolve();
    }
    return null;
  }

  static boolean hasAnnotation(PsiMethod psiMethod, String annotationName) {
    return findAnnotationOnMethod(psiMethod, annotationName) != null;
  }

  static PsiMethod findMethod(PsiElement element) {
    if (element == null) {
      return null;
    } else if (element instanceof PsiMethod) {
      return (PsiMethod) element;
    } else {
      return findMethod(element.getParent());
    }
  }
}
