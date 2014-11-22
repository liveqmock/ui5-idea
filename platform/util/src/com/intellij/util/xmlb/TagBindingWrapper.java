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
import org.jdom.Element;
import org.jdom.Text;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

//todo: merge with option tag binding
class TagBindingWrapper extends Binding {
  private final Binding myBinding;
  private final String myTagName;
  private final String myAttributeName;

  public TagBindingWrapper(@NotNull Binding binding, final String tagName, final String attributeName) {
    super(binding.myAccessor);

    myBinding = binding;

    //noinspection unchecked
    assert binding.getBoundNodeType().isAssignableFrom(Text.class);
    myTagName = tagName;
    myAttributeName = attributeName;
  }

  @Nullable
  @Override
  public Object serialize(Object o, @Nullable Object context, SerializationFilter filter) {
    Element e = new Element(myTagName);
    Content content = (Content)myBinding.serialize(o, e, filter);
    if (content != null) {
      if (!myAttributeName.isEmpty()) {
        e.setAttribute(myAttributeName, content.getValue());
      }
      else if (content instanceof Text) {
        e.addContent(content);
      }
      else {
        e.addContent(content.getValue());
      }
    }
    return e;
  }

  @Override
  public Object deserialize(Object context, @NotNull Object node) {
    Element element = (Element)node;
    if (myAttributeName.isEmpty()) {
      return Binding.deserializeList(myBinding, context, XmlSerializerImpl.getFilteredContent(element));
    }
    else {
      return myBinding.deserialize(context, element.getAttribute(myAttributeName));
    }
  }

  @Override
  public boolean isBoundTo(Object node) {
    return node instanceof Element && ((Element)node).getName().equals(myTagName);
  }

  @Override
  public Class getBoundNodeType() {
    return Element.class;
  }
}
