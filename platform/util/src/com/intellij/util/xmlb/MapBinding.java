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

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.xmlb.annotations.MapAnnotation;
import org.jdom.Attribute;
import org.jdom.Content;
import org.jdom.Element;
import org.jdom.Text;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

import static com.intellij.util.xmlb.Constants.*;

class MapBinding extends Binding implements MultiNodeBinding {
  private static final Logger LOG = Logger.getInstance(MapBinding.class);

  private static final Comparator<Object> KEY_COMPARATOR = new Comparator<Object>() {
    @SuppressWarnings({"unchecked", "NullableProblems"})
    @Override
    public int compare(Object o1, Object o2) {
      if (o1 instanceof Comparable && o2 instanceof Comparable) {
        Comparable c1 = (Comparable)o1;
        Comparable c2 = (Comparable)o2;
        return c1.compareTo(c2);
      }
      return 0;
    }
  };

  private final Binding myKeyBinding;
  private final Binding myValueBinding;
  private final MapAnnotation myMapAnnotation;

  public MapBinding(ParameterizedType type, @NotNull Accessor accessor) {
    super(accessor);

    Type[] arguments = type.getActualTypeArguments();
    Type keyType = arguments[0];
    Type valueType = arguments[1];

    myKeyBinding = XmlSerializerImpl.getBinding(keyType);
    myValueBinding = XmlSerializerImpl.getBinding(valueType);
    myMapAnnotation = accessor.getAnnotation(MapAnnotation.class);
  }

  @Override
  public boolean isMulti() {
    return true;
  }

  @Nullable
  @Override
  public Object serialize(Object o, @Nullable Object context, SerializationFilter filter) {
    Map map = (Map)o;
    Element m = myMapAnnotation == null || myMapAnnotation.surroundWithTag() ? new Element(MAP) : (Element)context;
    assert m != null;

    final Object[] keys = ArrayUtil.toObjectArray(map.keySet());
    if (myMapAnnotation == null || myMapAnnotation.sortBeforeSave()) {
      Arrays.sort(keys, KEY_COMPARATOR);
    }

    for (Object k : keys) {
      Object v = map.get(k);

      Element entry = new Element(getEntryAttributeName());
      m.addContent(entry);

      Object kNode = myKeyBinding.serialize(k, entry, filter);
      if (kNode instanceof Text) {
        entry.setAttribute(getKeyAttributeName(), ((Text)kNode).getText());
      }
      else if (kNode != null) {
        if (myMapAnnotation != null && !myMapAnnotation.surroundKeyWithTag()) {
          entry.addContent((Content)kNode);
        }
        else {
          Element key = new Element(getKeyAttributeName());
          entry.addContent(key);
          key.addContent((Content)kNode);
        }
      }

      Object vNode = myValueBinding.serialize(v, entry, filter);
      if (vNode instanceof Text) {
        entry.setAttribute(getValueAttributeName(), ((Text)vNode).getText());
      }
      else if (vNode != null) {
        if (myMapAnnotation != null && !myMapAnnotation.surroundValueWithTag()) {
          entry.addContent((Element)vNode);
        }
        else {
          Element value = new Element(getValueAttributeName());
          entry.addContent(value);
          value.addContent((Content)vNode);
        }
      }
    }

    return m == context ? null : m;
  }

  private String getEntryAttributeName() {
    return myMapAnnotation == null ? ENTRY : myMapAnnotation.entryTagName();
  }

  private String getKeyAttributeName() {
    return myMapAnnotation == null ? KEY : myMapAnnotation.keyAttributeName();
  }

  private String getValueAttributeName() {
    return myMapAnnotation == null ? VALUE : myMapAnnotation.valueAttributeName();
  }

  @Nullable
  @Override
  public Object deserializeList(Object context, @NotNull List<?> nodes) {
    List<?> childNodes;
    if (myMapAnnotation == null || myMapAnnotation.surroundWithTag()) {
      assert nodes.size() == 1;
      Element m = (Element)nodes.get(0);
      childNodes = m.getContent();
    }
    else {
      childNodes = nodes;
    }
    return deserialize(context, childNodes);
  }

  @Override
  public Object deserialize(Object context, @NotNull Object node) {
    if (myMapAnnotation == null || myMapAnnotation.surroundWithTag()) {
      return deserialize(context, ((Element)node).getContent());
    }
    else {
      return deserialize(context, Collections.singletonList((Element)node));
    }
  }

  private Map deserialize(Object context, List<?> childNodes) {
    Map map = (Map)context;
    map.clear();

    for (Object childNode : childNodes) {
      if (XmlSerializerImpl.isIgnoredNode(childNode)) {
        continue;
      }

      Element entry = (Element)childNode;

      Object k = null;
      Object v = null;

      if (!entry.getName().equals(getEntryAttributeName())) {
        LOG.warn("unexpected entry for serialized Map will be skipped: " + entry);
        continue;
      }

      Attribute keyAttr = entry.getAttribute(getKeyAttributeName());
      if (keyAttr != null) {
        k = myKeyBinding.deserialize(context, keyAttr);
      }
      else {
        if (myMapAnnotation != null && !myMapAnnotation.surroundKeyWithTag()) {
          for (Object child : JDOMUtil.getContent(entry)) {
            if (myKeyBinding.isBoundTo(child)) {
              k = myKeyBinding.deserialize(context, child);
              break;
            }
          }
        }
        else {
          k = Binding.deserializeList(myKeyBinding, context, XmlSerializerImpl.getFilteredContent(entry.getChild(getKeyAttributeName())));
        }
      }

      Attribute valueAttr = entry.getAttribute(getValueAttributeName());
      if (valueAttr != null) {
        v = myValueBinding.deserialize(context, valueAttr);
      }
      else {
        if (myMapAnnotation != null && !myMapAnnotation.surroundValueWithTag()) {
          for (Content child : entry.getContent()) {
            if (myValueBinding.isBoundTo(child)) {
              v = myValueBinding.deserialize(context, child);
              break;
            }
          }
        }
        else {
          v = Binding.deserializeList(myValueBinding, context, XmlSerializerImpl.getFilteredContent(entry.getChild(getValueAttributeName())));
        }
      }

      //noinspection unchecked
      map.put(k, v);
    }
    return map;
  }

  @Override
  public boolean isBoundTo(Object node) {
    if (!(node instanceof Element)) return false;

    if (myMapAnnotation != null && !myMapAnnotation.surroundWithTag()) {
      return myMapAnnotation.entryTagName().equals(((Element)node).getName());
    }

    return ((Element)node).getName().equals(MAP);
  }

  @Override
  public Class getBoundNodeType() {
    return Element.class;
  }
}
