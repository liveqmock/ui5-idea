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
package com.intellij.util.xmlb;

import com.intellij.util.ThreeState;
import gnu.trove.TObjectFloatHashMap;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;

public final class SmartSerializer {
  private final LinkedHashSet<String> mySerializedAccessorNameTracker;
  private TObjectFloatHashMap<String> myOrderedBindings;
  private final SerializationFilter mySerializationFilter;

  public SmartSerializer(boolean trackSerializedNames, boolean useSkipEmptySerializationFilter) {
    mySerializedAccessorNameTracker = trackSerializedNames ? new LinkedHashSet<String>() : null;

    mySerializationFilter = useSkipEmptySerializationFilter ?
                            new SkipEmptySerializationFilter() {
                              @Override
                              protected ThreeState accepts(@NotNull String name, @NotNull Object beanValue) {
                                return mySerializedAccessorNameTracker != null && mySerializedAccessorNameTracker.contains(name) ? ThreeState.YES : ThreeState.UNSURE;
                              }
                            } :
                            new SkipDefaultValuesSerializationFilters() {
                              @Override
                              protected boolean accepts(@NotNull Accessor accessor, @NotNull Object bean, @Nullable Object beanValue) {
                                if (mySerializedAccessorNameTracker != null && mySerializedAccessorNameTracker.contains(accessor.getName())) {
                                  return true;
                                }
                                return super.accepts(accessor, bean, beanValue);
                              }
                            };
  }

  public SmartSerializer() {
    this(true, false);
  }

  public void readExternal(@NotNull Object bean, @NotNull Element element) {
    if (mySerializedAccessorNameTracker != null) {
      mySerializedAccessorNameTracker.clear();
      myOrderedBindings = null;
    }

    BeanBinding beanBinding = getBinding(bean);
    beanBinding.deserializeInto(bean, element, mySerializedAccessorNameTracker);

    if (mySerializedAccessorNameTracker != null) {
      myOrderedBindings = beanBinding.computeBindingWeights(mySerializedAccessorNameTracker);
    }
  }

  public void writeExternal(@NotNull Object bean, @NotNull Element element) {
    BeanBinding binding = getBinding(bean);
    if (myOrderedBindings != null) {
      binding.sortBindings(myOrderedBindings);
    }
    binding.serializeInto(bean, element, mySerializationFilter);
  }

  @NotNull
  private static BeanBinding getBinding(@NotNull Object bean) {
    return (BeanBinding)XmlSerializerImpl.getBinding(bean.getClass());
  }
}