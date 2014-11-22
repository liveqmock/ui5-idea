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
package com.intellij.openapi.components.impl.stores;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.intellij.openapi.components.impl.ComponentManagerImpl;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleTypeManager;
import com.intellij.openapi.module.impl.ModuleImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectEx;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtil;
import com.intellij.util.PathUtilRt;
import com.intellij.util.messages.MessageBus;
import gnu.trove.THashMap;
import org.jdom.Attribute;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

public class ModuleStoreImpl extends BaseFileConfigurableStoreImpl implements IModuleStore {
  private static final Logger LOG = Logger.getInstance(ModuleStoreImpl.class);

  private final ModuleImpl myModule;

  @SuppressWarnings({"UnusedDeclaration"})
  public ModuleStoreImpl(final ComponentManagerImpl componentManager, final ModuleImpl module) {
    super(componentManager);
    myModule = module;
  }

  @Override
  protected FileBasedStorage getMainStorage() {
    FileBasedStorage storage = (FileBasedStorage)getStateStorageManager().getStateStorage(StoragePathMacros.MODULE_FILE, RoamingType.PER_USER);
    assert storage != null;
    return storage;
  }

  @Override
  protected Project getProject() {
    return myModule.getProject();
  }

  @Override
  public void load() throws IOException, StateStorageException {
    super.load();

    final ModuleFileData storageData = getMainStorageData();
    final String moduleTypeId = storageData.myOptions.get(Module.ELEMENT_TYPE);
    myModule.setOption(Module.ELEMENT_TYPE, ModuleTypeManager.getInstance().findByID(moduleTypeId).getId());

    if (ApplicationManager.getApplication().isHeadlessEnvironment() || ApplicationManager.getApplication().isUnitTestMode()) return;

    final TrackingPathMacroSubstitutor substitutor = getStateStorageManager().getMacroSubstitutor();
    if (substitutor != null) {
      final Collection<String> macros = substitutor.getUnknownMacros(null);
      if (!macros.isEmpty()) {
        final Project project = myModule.getProject();

          StartupManager.getInstance(project).runWhenProjectIsInitialized(new Runnable() {
            @Override
            public void run() {
              StorageUtil.notifyUnknownMacros(substitutor, project, null);
            }
          });
      }
    }
  }

  @Override
  public ModuleFileData getMainStorageData() throws StateStorageException {
    return (ModuleFileData)super.getMainStorageData();
  }

  static class ModuleFileData extends BaseStorageData {
    private final Map<String, String> myOptions;
    private final Module myModule;

    public ModuleFileData(final String rootElementName, Module module) {
      super(rootElementName);
      myModule = module;
      myOptions = new THashMap<String, String>(2);
    }

    protected ModuleFileData(final ModuleFileData storageData) {
      super(storageData);

      myOptions = new THashMap<String, String>(storageData.myOptions);
      myModule = storageData.myModule;
    }

    @Override
    public void load(@NotNull Element rootElement, @Nullable PathMacroSubstitutor pathMacroSubstitutor, boolean intern) {
      super.load(rootElement, pathMacroSubstitutor, intern);

      for (Attribute attribute : rootElement.getAttributes()) {
        myOptions.put(attribute.getName(), attribute.getValue());
      }
    }

    @Override
    @NotNull
    protected Element save(@NotNull Map<String, Element> newLiveStates) {
      Element root = super.save(newLiveStates);
      myOptions.put(VERSION_OPTION, Integer.toString(myVersion));
      String[] options = ArrayUtil.toStringArray(myOptions.keySet());
      Arrays.sort(options);
      for (String option : options) {
        root.setAttribute(option, myOptions.get(option));
      }

      //need be last for compat reasons
      root.removeAttribute(VERSION_OPTION);
      root.setAttribute(VERSION_OPTION, Integer.toString(myVersion));

      return root;
    }

    @Override
    public StorageData clone() {
      return new ModuleFileData(this);
    }

    @Nullable
    @Override
    public Set<String> getChangedComponentNames(@NotNull StorageData newStorageData, @Nullable PathMacroSubstitutor substitutor) {
      final ModuleFileData data = (ModuleFileData)newStorageData;
      if (!myOptions.equals(data.myOptions)) {
        return null;
      }
      return super.getChangedComponentNames(newStorageData, substitutor);
    }

    public void setOption(final String optionName, final String optionValue) {
      myOptions.put(optionName, optionValue);
    }

    public void clearOption(final String optionName) {
      myOptions.remove(optionName);
    }

    public String getOptionValue(final String optionName) {
      return myOptions.get(optionName);
    }
  }

  @Override
  public void setModuleFilePath(@NotNull String filePath) {
    final String path = filePath.replace(File.separatorChar, '/');
    LocalFileSystem.getInstance().refreshAndFindFileByPath(path);
    final StateStorageManager storageManager = getStateStorageManager();
    storageManager.clearStateStorage(StoragePathMacros.MODULE_FILE);
    storageManager.addMacro(StoragePathMacros.MODULE_FILE, path);
  }

  @Override
  @Nullable
  public VirtualFile getModuleFile() {
    return getMainStorage().getVirtualFile();
  }

  @Override
  @NotNull
  public String getModuleFilePath() {
    return getMainStorage().getFilePath();
  }

  @Override
  @NotNull
  public String getModuleFileName() {
    return PathUtilRt.getFileName(getMainStorage().getFilePath());
  }

  @Override
  public void setOption(final String optionName, final String optionValue) {
    try {
      getMainStorageData().setOption(optionName,  optionValue);
    }
    catch (StateStorageException e) {
      LOG.error(e);
    }
  }

  @Override
  public void clearOption(final String optionName) {
    try {
      getMainStorageData().clearOption(optionName);
    }
    catch (StateStorageException e) {
      LOG.error(e);
    }
  }

  @Override
  public String getOptionValue(final String optionName) {
    try {
      return getMainStorageData().getOptionValue(optionName);
    }
    catch (StateStorageException e) {
      LOG.error(e);
      return null;
    }
  }

  @Override
  protected boolean optimizeTestLoading() {
    return ((ProjectEx)myModule.getProject()).isOptimiseTestLoadSpeed();
  }

  @NotNull
  @Override
  protected MessageBus getMessageBus() {
    return myModule.getMessageBus();
  }

  @NotNull
  @Override
  protected StateStorageManager createStateStorageManager() {
    return new ModuleStateStorageManager(PathMacroManager.getInstance(getComponentManager()).createTrackingSubstitutor(), myModule);
  }
}
