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

import org.jdom.Content;
import org.jdom.Text;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class TextBinding extends Binding {
  private volatile Binding myBinding;

  public TextBinding(@NotNull Accessor accessor) {
    super(accessor);
  }

  @Nullable
  @Override
  public Object serialize(Object o, @Nullable Object context, SerializationFilter filter) {
    Object v = myAccessor.read(o);
    if (v == null) {
      return null;
    }
    Object node = myBinding.serialize(v, context, filter);
    if (node == null) {
      return null;
    }
    else if (node instanceof Text) {
      return node;
    }
    else {
      return new Text(((Content)node).getValue());
    }
  }

  @Override
  @Nullable
  public Object deserialize(Object context, @NotNull Object node) {
    myAccessor.write(context, myBinding.deserialize(context, node));
    return context;
  }

  @Override
  public boolean isBoundTo(Object node) {
    return node instanceof Text;
  }

  @Override
  public Class getBoundNodeType() {
    return Text.class;
  }

  @Override
  public void init() {
    myBinding = XmlSerializerImpl.getBinding(myAccessor);
    if (!Text.class.isAssignableFrom(myBinding.getBoundNodeType())) {
      throw new XmlSerializationException("Can't use attribute binding for non-text content: " + myAccessor);
    }
  }
}
