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

import com.intellij.openapi.application.ex.DecodeDefaultsUtil;
import com.intellij.openapi.components.PathMacroManager;
import com.intellij.openapi.components.StateStorage;
import com.intellij.openapi.components.StateStorageException;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.xmlb.JDOMXIncluder;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Set;

class DefaultsStateStorage implements StateStorage {
  private final PathMacroManager myPathMacroManager;

  public DefaultsStateStorage(@Nullable final PathMacroManager pathMacroManager) {
    myPathMacroManager = pathMacroManager;
  }

  @Nullable
  private Element getState(final Object component, final String componentName) throws StateStorageException {
    final URL url = DecodeDefaultsUtil.getDefaults(component, componentName);
    if (url == null) {
      return null;
    }

    try {
      Document document = JDOMUtil.loadDocument(url);
      document = JDOMXIncluder.resolve(document, url.toExternalForm());
      final Element documentElement = document.detachRootElement();

      if (myPathMacroManager != null) {
        myPathMacroManager.expandPaths(documentElement);
      }

      return documentElement;
    }
    catch (IOException e) {
      throw new StateStorageException("Error loading state from " + url, e);
    }
    catch (JDOMException e) {
      throw new StateStorageException("Error loading state from " + url, e);
    }
  }

  @Override
  @Nullable
  public <T> T getState(final Object component, @NotNull final String componentName, @NotNull final Class<T> stateClass, @Nullable final T mergeInto) {
    return DefaultStateSerializer.deserializeState(getState(component, componentName), stateClass, mergeInto);
  }

  @Override
  public boolean hasState(@Nullable final Object component, @NotNull final String componentName, final Class<?> aClass, final boolean reloadData) {
    return DecodeDefaultsUtil.getDefaults(component, componentName) != null;
  }

  @Override
  @Nullable
  public ExternalizationSession startExternalization() {
    return null;
  }

  @Override
  public void analyzeExternalChangesAndUpdateIfNeed(@NotNull Collection<VirtualFile> changedFiles, @NotNull Set<String> result) {
  }
}
