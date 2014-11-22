/*
 * Copyright 2000-2010 JetBrains s.r.o.
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

package com.intellij.execution.junit;

import com.intellij.execution.PatternConfigurationDelegate;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.configurations.ModuleBasedConfiguration;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.*;

import java.util.LinkedHashSet;
import java.util.Set;

public class PatternConfigurationProducer extends JUnitConfigurationProducer {
  private static PatternConfigurationDelegate ourDelegate = new PatternConfigurationDelegate() {
    @Override
    protected boolean isTestClass(PsiClass psiClass) {
      return JUnitUtil.isTestClass(psiClass);
    }

    @Override
    protected boolean isTestMethod(boolean checkAbstract, PsiElement psiElement) {
      return JUnitUtil.getTestMethod(psiElement, checkAbstract) != null;
    }
  }; 

  @Override
  protected boolean setupConfigurationFromContext(JUnitConfiguration configuration,
                                                  ConfigurationContext context,
                                                  Ref<PsiElement> sourceElement) {
    final LinkedHashSet<String> classes = new LinkedHashSet<String>();
    final PsiElement element = ourDelegate.checkPatterns(context, classes);
    if (element == null) {
      return false;
    }
    sourceElement.set(element);
    final JUnitConfiguration.Data data = configuration.getPersistentData();
    data.setPatterns(classes);
    data.TEST_OBJECT = JUnitConfiguration.TEST_PATTERN;
    data.setScope(setupPackageConfiguration(context, configuration, data.getScope()));
    configuration.setGeneratedName();
    return true;
  }

  @Override
  protected Module findModule(JUnitConfiguration configuration, Module contextModule) {
    final Set<String> patterns = configuration.getPersistentData().getPatterns();
    return findModule(configuration, contextModule, patterns);
  }

  @Override
  public boolean isConfigurationFromContext(JUnitConfiguration unitConfiguration, ConfigurationContext context) {
    final TestObject testobject = unitConfiguration.getTestObject();
    if (testobject instanceof TestsPattern) {
      final Set<String> patterns = unitConfiguration.getPersistentData().getPatterns();
      if (ourDelegate.isConfiguredFromContext(context, patterns)) return true;
    }
    return false;
  }

  public static Module findModule(ModuleBasedConfiguration configuration, Module contextModule, Set<String> patterns) {
    return ourDelegate.findModule(configuration, contextModule, patterns);
  }

  public static boolean isMultipleElementsSelected(ConfigurationContext context) {
    return ourDelegate.isMultipleElementsSelected(context);
  }

  public static Set<PsiElement> collectTestMembers(PsiElement[] elements, boolean checkAbstract) {
    return ourDelegate.collectTestMembers(elements, checkAbstract);
  }
}