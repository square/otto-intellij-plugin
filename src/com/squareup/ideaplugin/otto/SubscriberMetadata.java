package com.squareup.ideaplugin.otto;

import com.google.common.collect.ImmutableSet;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.Nullable;

/**
 * Encapsulates all of the metadata for a particular kind of subscriber method
 *
 * @author Steve Ash
 */
public class SubscriberMetadata {

  private static final ImmutableSet<SubscriberMetadata> subscribers = ImmutableSet.of(

      new SubscriberMetadata("com.squareup.otto.Subscribe", "com.squareup.otto.Bus", "com.squareup.otto.Produce",
          PickAction.Type.PRODUCER, PickAction.Type.EVENT_POST),

      new SubscriberMetadata("com.google.common.eventbus.Subscribe", "com.google.common.eventbus.EventBus", null,
          PickAction.Type.EVENT_POST)
  );

  public static ImmutableSet<SubscriberMetadata> getAllSubscribers() {
    return subscribers;
  }

  public static boolean isBusPostMethod(PsiMethod candidate, Project project) {
    if (!candidate.getName().equals("post"))
      return false; // it at least needs to be named post before bothering to do any more expensive analysis

    for (SubscriberMetadata subscriber : subscribers) {
      PsiMethod busPostMethod = subscriber.getBusPostMethod(project);
      if (candidate.equals(busPostMethod))
        return true;
    }
    return false;
  }

  @Nullable
  public static SubscriberMetadata getSubscriberMetadata(PsiMethod method) {
    for (SubscriberMetadata info : subscribers) {
      PsiAnnotation annotation = PsiConsultantImpl.findAnnotationOnMethod(method,
          info.getSubscriberAnnotationClassName());

      if (annotation != null)
        return info;
    }
    return null;
  }

  public static boolean isAnnotatedWithSubscriber(PsiMethod method) {
    return (getSubscriberMetadata(method) != null);
  }

  public static boolean isAnnotatedWithProducer(PsiMethod method) {
    for (SubscriberMetadata info : subscribers) {
      if (info.getProducerClassName() == null)
        continue;

      PsiAnnotation annotation = PsiConsultantImpl.findAnnotationOnMethod(method,
          info.getProducerClassName());

      if (annotation != null)
        return true;
    }
    return false;
  }

  private final String subscriberAnnotationClassName;
  private final String busClassName;
  private final String producerClassName;
  private final PickAction.Type[] displayedTypesOnSubscriberMethods;

  public SubscriberMetadata(String subscriberAnnotationClassName, String busClassName, String producerClassName,
                            PickAction.Type... displayedTypesOnSubscribers) {
    this.subscriberAnnotationClassName = subscriberAnnotationClassName;
    this.busClassName = busClassName;
    this.producerClassName = producerClassName;
    this.displayedTypesOnSubscriberMethods = displayedTypesOnSubscribers;
  }


  public String getSubscriberAnnotationClassName() {
    return subscriberAnnotationClassName;
  }

  public String getBusClassName() {
    return busClassName;
  }

  @Nullable
  public String getProducerClassName() {
    return producerClassName;
  }

  public PickAction.Type[] displayedTypesOnSubscriberMethods() {
    return displayedTypesOnSubscriberMethods;
  }

  @Nullable
  public PsiMethod getBusPostMethod(Project project) {
    JavaPsiFacade javaPsiFacade = JavaPsiFacade.getInstance(project);
    GlobalSearchScope globalSearchScope = GlobalSearchScope.allScope(project);

    PsiClass busClass = javaPsiFacade.findClass(getBusClassName(), globalSearchScope);
    if (busClass != null) {
      for (PsiMethod psiMethod : busClass.getMethods()) {
        if (psiMethod.getName().equals("post"))
          return psiMethod;
      }
    }
    return null;
  }
}
