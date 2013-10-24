package com.squareup.ideaplugin.otto;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.find.FindManager;
import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.find.findUsages.FindUsagesManager;
import com.intellij.find.findUsages.JavaClassFindUsagesOptions;
import com.intellij.find.impl.FindManagerImpl;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.AbstractProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiRecursiveElementVisitor;
import com.intellij.psi.PsiTreeChangeAdapter;
import com.intellij.psi.PsiTreeChangeEvent;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.ProjectScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageInfo2UsageAdapter;
import com.intellij.usages.UsageInfoToUsageConverter;
import com.intellij.util.Processor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;

public class OttoProjectHandler extends AbstractProjectComponent {

  private static final Key<OttoProjectHandler> KEY = Key.create(OttoProjectHandler.class.getName());
  public static final Logger LOGGER = Logger.getInstance(OttoProjectHandler.class);

  private final FindUsagesManager findUsagesManager;
  private final PsiManager psiManager;
  private final Map<VirtualFile, Set<String>> fileToEventClasses = new HashMap<VirtualFile, Set<String>>();
  private final Set<VirtualFile> filesToScan = new HashSet<VirtualFile>();
  private final Set<String> allEventClasses = new HashSet<String>();
  private final AtomicInteger startupScanAttemptsLeft = new AtomicInteger(5);

  public PsiTreeChangeAdapter listener;

  protected OttoProjectHandler(Project project, PsiManager psiManager) {
    super(project);
    this.findUsagesManager =
        ((FindManagerImpl) FindManager.getInstance(project)).getFindUsagesManager();
    this.psiManager = psiManager;
    project.putUserData(KEY, this);
    //System.out.println("OttoProjectHandler initialized");
  }

  public static OttoProjectHandler get(Project project) {
    return project.getUserData(KEY);
  }

  public boolean isEventClass(String className) {
    maybeRecomputeEventClasses();

    synchronized (allEventClasses) {
      return allEventClasses.contains(className);
    }
  }

  @Override public void projectOpened() {
    final AtomicBoolean hasRun = new AtomicBoolean(false);

    ApplicationManager.getApplication().invokeLater(
        new Runnable() {
          @Override public void run() {
            if (myProject.isInitialized()) {
              hasRun.set(true);
              findEventsViaMethodsAnnotatedSubscribe();

              psiManager.addPsiTreeChangeListener(listener = new MyPsiTreeChangeAdapter());
            }
          }
        }, new Condition() {
          @Override public boolean value(Object o) {
            return hasRun.get();
          }
        }
    );
  }

  private void findEventsViaMethodsAnnotatedSubscribe() {
    GlobalSearchScope projectScope = ProjectScope.getProjectScope(myProject);
    for (SubscriberMetadata subscriberMetadata : SubscriberMetadata.getAllSubscribers()) {
      performSearch(projectScope, subscriberMetadata.getSubscriberAnnotationClassName());
    }
  }

  private void performSearch(final SearchScope searchScope, final String subscribeClassName) {
    JavaPsiFacade javaPsiFacade = JavaPsiFacade.getInstance(myProject);
    PsiClass subscribePsiClass = javaPsiFacade.findClass(subscribeClassName,
        GlobalSearchScope.allScope(myProject));
    if (subscribePsiClass == null) {
      // the guava or otto library isn't available in this project so do nothing
      return;
    }

    FindUsagesHandler handler = findUsagesManager.getNewFindUsagesHandler(subscribePsiClass, false);
    if (handler != null) {
      UsageInfoToUsageConverter.TargetElementsDescriptor descriptor =
          new UsageInfoToUsageConverter.TargetElementsDescriptor(handler.getPrimaryElements(),
              handler.getSecondaryElements());

      final long startTime = System.currentTimeMillis();
      Processor<Usage> processor = new Processor<Usage>() {
        @Override public boolean process(Usage usage) {
          if (usage instanceof UsageInfo2UsageAdapter) {
            PsiElement element = ((UsageInfo2UsageAdapter) usage).getElement();
            if ((element = element.getContext()) instanceof PsiAnnotation) {
              if ((element = element.getContext()) instanceof PsiModifierList) {
                if ((element = element.getContext()) instanceof PsiMethod) {
                  if (PsiConsultantImpl.hasAnnotation((PsiMethod) element, subscribeClassName)) {
                    maybeAddSubscriberMethod((PsiMethod) element);
                  }
                }
              }
            }
          }
          return true;
        }
      };
      JavaClassFindUsagesOptions options = new JavaClassFindUsagesOptions(myProject);
      options.searchScope = searchScope;
      FindUsagesManager.startProcessUsages(handler, descriptor, processor, options, new Runnable() {
        @Override public void run() {
          int eventClassCount = optimizeEventClassIndex();
          if (eventClassCount > 0) {
            scheduleRefreshOfEventFiles();
          } else {
            maybeScheduleAnotherSearch();
          }

          if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(String.format("Searched for @Subscribe in %s in %dms",
                searchScope, System.currentTimeMillis() - startTime));
          }
        }
      });
    }
  }

  private void maybeScheduleAnotherSearch() {
    if (startupScanAttemptsLeft.decrementAndGet() > 0) {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        @Override
        public void run() {
          findEventsViaMethodsAnnotatedSubscribe();
        }
      });
    }
  }

  private void maybeRecomputeEventClasses() {
    List<VirtualFile> myFilesToScan;
    synchronized (filesToScan) {
      if (filesToScan.isEmpty()) return;

      myFilesToScan = new ArrayList<VirtualFile>(filesToScan);
      filesToScan.clear();
    }

    for (VirtualFile virtualFile : myFilesToScan) {
      synchronized (fileToEventClasses) {
        getEventClasses(virtualFile).clear();
      }
      PsiFile psiFile = PsiManager.getInstance(myProject).findFile(virtualFile);
      if (psiFile == null) throw new IllegalStateException("huh? " + virtualFile);
      if (psiFile.getFileType() instanceof JavaFileType) {

        final long startTime = System.currentTimeMillis();
        psiFile.accept(new PsiRecursiveElementVisitor() {
          @Override public void visitElement(PsiElement element) {
            if (element instanceof PsiMethod
                && SubscriberMetadata.isAnnotatedWithSubscriber((PsiMethod) element)) {
              maybeAddSubscriberMethod((PsiMethod) element);
            } else {
              super.visitElement(element);
            }
          }
        });
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug(String.format("Searched for @Subscribe in %s in %dms",
              virtualFile, System.currentTimeMillis() - startTime));
        }
      }
    }

    optimizeEventClassIndex();
  }

  private int optimizeEventClassIndex() {
    synchronized (allEventClasses) {
      allEventClasses.clear();

      synchronized (fileToEventClasses) {
        for (Set<String> strings : fileToEventClasses.values()) {
          allEventClasses.addAll(strings);
        }
      }
      return allEventClasses.size();
    }
  }

  private void scheduleRefreshOfEventFiles() {
    ApplicationManager.getApplication().invokeLater(new Runnable() {
      @Override
      public void run() {
        Set<String> eventClasses;
        synchronized (allEventClasses) {
          eventClasses = new HashSet<String>(allEventClasses);
        }
        JavaPsiFacade javaPsiFacade = JavaPsiFacade.getInstance(myProject);
        DaemonCodeAnalyzer codeAnalyzer = DaemonCodeAnalyzer.getInstance(myProject);
        for (String eventClass : eventClasses) {
          PsiClass eventPsiClass = javaPsiFacade.findClass(eventClass,
                GlobalSearchScope.allScope(myProject));
          if (eventPsiClass == null) continue;
          PsiFile psiFile = eventPsiClass.getContainingFile();
          if (psiFile == null) continue;
          codeAnalyzer.restart(psiFile);
        }
      }
    });
  }

  private void maybeAddSubscriberMethod(PsiMethod element) {
    PsiTypeElement methodParameter = OttoLineMarkerProvider.getMethodParameter(element);
    if (methodParameter != null) {
      String canonicalText = methodParameter.getType().getCanonicalText();
      VirtualFile virtualFile = methodParameter.getContainingFile().getVirtualFile();
      synchronized (fileToEventClasses) {
        Set<String> eventClasses = getEventClasses(virtualFile);
        eventClasses.add(canonicalText);
      }
    }
  }

  private Set<String> getEventClasses(VirtualFile virtualFile) {
    Set<String> eventClasses = fileToEventClasses.get(virtualFile);
    if (eventClasses == null) {
      eventClasses = new HashSet<String>();
      fileToEventClasses.put(virtualFile, eventClasses);
    }
    return eventClasses;
  }

  @Override public void projectClosed() {
    if (listener != null) psiManager.removePsiTreeChangeListener(listener);
  }

  private class MyPsiTreeChangeAdapter extends PsiTreeChangeAdapter {
    @Override public void childAdded(@NotNull PsiTreeChangeEvent event) {
      maybeInvalidate(event);
    }

    @Override public void childMoved(@NotNull PsiTreeChangeEvent event) {
      maybeInvalidate(event);
    }

    @Override public void childRemoved(@NotNull PsiTreeChangeEvent event) {
      maybeInvalidate(event);
    }

    @Override public void childrenChanged(@NotNull PsiTreeChangeEvent event) {
      maybeInvalidate(event);
    }

    @Override public void childReplaced(@NotNull PsiTreeChangeEvent event) {
      maybeInvalidate(event);
    }

    @Override public void propertyChanged(@NotNull PsiTreeChangeEvent event) {
      maybeInvalidate(event);
    }
  }

  private void maybeInvalidate(PsiTreeChangeEvent event) {
    PsiFile file = event.getFile();
    if (file == null) {
      return;
    }

    VirtualFile virtualFile = file.getVirtualFile();
    synchronized (filesToScan) {
      filesToScan.add(virtualFile);
    }
  }
}
