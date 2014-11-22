/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package org.jetbrains.plugins.coursecreator.actions;

import com.intellij.execution.*;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.icons.AllIcons;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.PathUtil;
import com.intellij.util.containers.HashMap;
import com.jetbrains.python.run.PythonConfigurationType;
import com.jetbrains.python.run.PythonRunConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.coursecreator.CCProjectService;
import org.jetbrains.plugins.coursecreator.format.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

public class CCRunTestsAction extends AnAction {
  private static final Logger LOG = Logger.getInstance(CCRunTestsAction.class.getName());

  public CCRunTestsAction() {
    getTemplatePresentation().setIcon(AllIcons.Actions.Lightning);
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Presentation presentation = e.getPresentation();
    presentation.setText("");
    presentation.setVisible(false);
    presentation.setEnabled(false);

    final ConfigurationContext context = ConfigurationContext.getFromContext(e.getDataContext());
    Location location = context.getLocation();
    if (location == null) {
      return;
    }
    PsiElement psiElement = location.getPsiElement();
    PsiFile psiFile = psiElement.getContainingFile();
    Project project = e.getProject();
    if (project == null || psiFile == null) {
      presentation.setVisible(false);
      presentation.setEnabled(false);
      return;
    }
    final CCProjectService service = CCProjectService.getInstance(project);
    final Course course = service.getCourse();
    final PsiDirectory taskDir = psiFile.getContainingDirectory();
    final PsiDirectory lessonDir = taskDir.getParent();
    if (lessonDir == null) return;
    if (course == null) return;
    final Lesson lesson = course.getLesson(lessonDir.getName());
    if (lesson == null) return;
    final Task task = lesson.getTask(taskDir.getName());
    if (task == null) {
      presentation.setVisible(false);
      presentation.setEnabled(false);
      return;
    }
    TaskFile taskFile = task.getTaskFile(psiFile.getName());
    if (taskFile == null) {
      LOG.info("could not find task file");
      presentation.setVisible(false);
      presentation.setEnabled(false);
      return;
    }
    if (psiFile.getName().contains(".answer")) {
      presentation.setEnabled(true);
      presentation.setVisible(true);
      presentation.setText("Run tests from '" + psiFile.getName() + "'");
    }
    else {
      presentation.setEnabled(false);
      presentation.setVisible(false);
    }
  }

  public void actionPerformed(@NotNull AnActionEvent e) {
    final ConfigurationContext context = ConfigurationContext.getFromContext(e.getDataContext());
    run(context);
  }

  public static void run(final @NotNull ConfigurationContext context) {
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {
        final Project project = context.getProject();
        PsiElement location = context.getPsiLocation();
        final Course course = CCProjectService.getInstance(project).getCourse();
        if (course == null || location == null) {
          return;
        }
        PsiFile psiFile = location.getContainingFile();
        final VirtualFile virtualFile = psiFile.getVirtualFile();
        final VirtualFile taskDir = virtualFile.getParent();
        if (taskDir == null) {
          return;
        }
        final Task task = getTask(course, taskDir);
        if (task == null) {
          return;
        }
        clearTestEnvironment(taskDir, project);
        for (final Map.Entry<String, TaskFile> entry : task.getTaskFiles().entrySet()) {
          final String name = entry.getKey();
          createTestEnvironment(taskDir, name, entry.getValue(), project);
          VirtualFile testFile = taskDir.findChild("tests.py");
          if (testFile == null) {
            return;
          }
          executeTests(project, virtualFile, taskDir, testFile);
        }
      }
    });
  }

  private static void createTestEnvironment(@NotNull final VirtualFile taskDir, final String fileName, @NotNull final TaskFile taskFile,
                                            @NotNull final Project project) {
    try {
      String answerFileName = FileUtil.getNameWithoutExtension(fileName) + ".answer";
      final VirtualFile answerFile = taskDir.findChild(answerFileName);
      if (answerFile == null) {
        LOG.debug("could not find answer file " + answerFileName);
        return;
      }
      ApplicationManager.getApplication().runWriteAction(new Runnable() {
        @Override
        public void run() {
          final FileDocumentManager documentManager = FileDocumentManager.getInstance();
          documentManager.saveAllDocuments();
        }
      });
      answerFile.copy(project, taskDir, fileName);
      flushWindows(taskFile, answerFile);
      createResourceFiles(answerFile, project);
    }
    catch (IOException e) {
      LOG.error(e);
    }
  }

  public static void clearTestEnvironment(@NotNull final VirtualFile taskDir, @NotNull final Project project) {
    try {
      VirtualFile ideaDir = project.getBaseDir().findChild(".idea");
      if (ideaDir == null) {
        LOG.debug("idea directory doesn't exist");
        return;
      }
      VirtualFile courseResourceDir = ideaDir.findChild("course");
      if (courseResourceDir != null) {
        courseResourceDir.delete(project);
      }
      VirtualFile[] taskDirChildren = taskDir.getChildren();
      for (VirtualFile file : taskDirChildren) {
        if (file.getName().contains("_windows")) {
          file.delete(project);
        }
        if (CCProjectService.getInstance(project).isTaskFile(file)) {
          file.delete(project);
        }
      }
    }
    catch (IOException e) {
      LOG.error(e);
    }
  }

  private static void executeTests(@NotNull final Project project,
                                   @NotNull final VirtualFile virtualFile,
                                   @NotNull final VirtualFile taskDir,
                                   @NotNull final VirtualFile testFile) {
    final ConfigurationFactory factory = PythonConfigurationType.getInstance().getConfigurationFactories()[0];
    final RunnerAndConfigurationSettings settings =
      RunManager.getInstance(project).createRunConfiguration("test", factory);

    final PythonRunConfiguration configuration = (PythonRunConfiguration)settings.getConfiguration();
    configuration.setScriptName(testFile.getPath());
    VirtualFile userFile = taskDir.findChild(virtualFile.getNameWithoutExtension() + ".py");
    if (userFile == null) {
      return;
    }
    VirtualFile ideaDir = project.getBaseDir().findChild(".idea");
    if (ideaDir == null) {
      return;
    }
    VirtualFileManager.getInstance().refreshWithoutFileWatcher(true);
    ProjectView.getInstance(project).refresh();
    VirtualFile courseDir = ideaDir.findChild("course");
    if (courseDir == null) {
      return;
    }
    configuration.setScriptParameters(PathUtil.toSystemDependentName(courseDir.getPath()) + " " + PathUtil.toSystemDependentName(userFile.getPath()));
    Executor executor = DefaultRunExecutor.getRunExecutorInstance();
    ProgramRunnerUtil.executeConfiguration(project, settings, executor);
  }

  @Nullable
  private static Task getTask(@NotNull final Course course, @NotNull final VirtualFile taskDir) {
    if (!taskDir.getName().contains("task")) {
      return null;
    }
    VirtualFile lessonDir = taskDir.getParent();
    if (lessonDir == null || !lessonDir.getName().contains("lesson")) {
      return null;
    }
    Lesson lesson = course.getLesson(lessonDir.getName());
    if (lesson == null) {
      return null;
    }
    return lesson.getTask(taskDir.getName());
  }


  //some tests could compare task files after user modifications with initial task files
  private static void createResourceFiles(@NotNull final VirtualFile file, @NotNull final Project project) {
    VirtualFile taskDir = file.getParent();
    int index = CCProjectService.getIndex(taskDir.getName(), "task");
    VirtualFile lessonDir = taskDir.getParent();
    int lessonIndex = CCProjectService.getIndex(lessonDir.getName(), "lesson");
    Course course = CCProjectService.getInstance(project).getCourse();
    if (course == null) {
      return;
    }
    VirtualFile ideaDir = project.getBaseDir().findChild(".idea");
    assert ideaDir != null;
    try {
      VirtualFile courseResourceDir = findOrCreateDir(project, ideaDir, "course");
      VirtualFile lessonResourceDir = findOrCreateDir(project, courseResourceDir, lessonDir.getName());
      VirtualFile taskResourceDir = findOrCreateDir(project, lessonResourceDir, taskDir.getName());
      if (CCProjectService.indexIsValid(lessonIndex, course.getLessons())) {
        Lesson lesson = course.getLessons().get(lessonIndex);
        if (CCProjectService.indexIsValid(index, lesson.getTaskList())) {
          Task task = lesson.getTaskList().get(index);
          HashMap<TaskFile, TaskFile> taskFilesCopy = new HashMap<TaskFile, TaskFile>();
          for (Map.Entry<String, TaskFile> entry : task.getTaskFiles().entrySet()) {
            CCCreateCourseArchive.createUserFile(project, taskFilesCopy, taskResourceDir, taskDir, entry);
            CCCreateCourseArchive.resetTaskFiles(taskFilesCopy);
          }
        }
      }
    }
    catch (IOException e) {
      LOG.error(e);
    }
  }

  private static VirtualFile findOrCreateDir(@NotNull final Project project, @NotNull final VirtualFile dir, String name) throws IOException {
    VirtualFile targetDir = dir.findChild(name);
    if (targetDir == null) {
      targetDir = dir.createChildDirectory(project, name);
    }
    return targetDir;
  }

  @SuppressWarnings("IOResourceOpenedButNotSafelyClosed")
  public static VirtualFile flushWindows(TaskFile taskFile, VirtualFile file) {
    VirtualFile taskDir = file.getParent();
    VirtualFile fileWindows = null;
    final Document document = FileDocumentManager.getInstance().getDocument(file);
    if (document == null) {
      LOG.debug("Couldn't flush windows");
      return null;
    }
    if (taskDir != null) {
      String name = file.getNameWithoutExtension() + "_windows";
      PrintWriter printWriter = null;
      try {
        fileWindows = taskDir.createChildData(taskFile, name);
        printWriter = new PrintWriter(new FileOutputStream(fileWindows.getPath()));
        for (TaskWindow taskWindow : taskFile.getTaskWindows()) {
          int start = taskWindow.getRealStartOffset(document);
          String windowDescription = document.getText(new TextRange(start, start + taskWindow.getReplacementLength()));
          printWriter.println("#educational_plugin_window = " + windowDescription);
        }
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          @Override
          public void run() {
            FileDocumentManager.getInstance().saveDocument(document);
          }
        });
      }
      catch (IOException e) {
        LOG.error(e);
      }
      finally {
        if (printWriter != null) {
          printWriter.close();
        }
      }
    }
    return fileWindows;
  }
}
