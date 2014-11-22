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

import com.intellij.util.xmlb.annotations.Attribute;
import org.jdom.Content;
import org.jdom.Text;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class AttributeBinding extends BasePrimitiveBinding {
  public AttributeBinding(@NotNull Accessor accessor, @NotNull Attribute attribute) {
    super(accessor, attribute.value(), attribute.converter());
  }

  @Override
  @Nullable
  public Object serialize(@NotNull Object o, @Nullable Object context, SerializationFilter filter) {
    Object value = myAccessor.read(o);
    if (value == null) {
      return null;
    }

    String stringValue;
    if (myConverter != null) {
      stringValue = myConverter.toString(value);
    }
    else {
      assert myBinding != null;
      Content content = (Content)myBinding.serialize(value, context, filter);
      if (content == null) {
        return null;
      }

      stringValue = content.getValue();
    }
    return new org.jdom.Attribute(myName, stringValue);
  }

  @Override
  @Nullable
  public Object deserialize(Object context, @NotNull Object node) {
    org.jdom.Attribute attribute = (org.jdom.Attribute)node;
    assert isBoundTo(attribute);
    Object value;
    if (myConverter != null) {
      value = myConverter.fromString(attribute.getValue());
    }
    else {
      assert myBinding != null;
      value = myBinding.deserialize(context, new Text(attribute.getValue()));
    }
    myAccessor.write(context, value);
    return context;
  }

  @Override
  public boolean isBoundTo(Object node) {
    return node instanceof org.jdom.Attribute && ((org.jdom.Attribute)node).getName().equals(myName);
  }

  @Override
  public Class getBoundNodeType() {
    return org.jdom.Attribute.class;
  }

  @Override
  public void init() {
    super.init();
    if (myBinding != null && !Text.class.isAssignableFrom(myBinding.getBoundNodeType())) {
      throw new XmlSerializationException("Can't use attribute binding for non-text content: " + myAccessor);
    }
  }

  public String toString() {
    return "AttributeBinding[" + myName + ", binding=" + myBinding + "]";
  }
}
