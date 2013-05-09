package com.squareup.ideaplugin.otto;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiType;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageInfo2UsageAdapter;

public class BusPostDecider implements Decider {

  private PsiClass eventClass;

  public BusPostDecider(PsiClass eventClass) {
    this.eventClass = eventClass;
  }

  @Override public boolean shouldShow(Usage usage) {
    PsiElement element = ((UsageInfo2UsageAdapter) usage).getElement();
    PsiMethodCallExpression methodCall = PsiConsultantImpl.findMethodCall(element);
    if (methodCall != null) {
      PsiType[] expressionTypes = methodCall.getArgumentList().getExpressionTypes();
      for (PsiType expressionType : expressionTypes) {
        PsiClass argumentEventClass = PsiConsultantImpl.getClass(expressionType);
        if (argumentEventClass.equals(this.eventClass)) {
          return true;
        }
      }
    }

    return false;
  }
}
