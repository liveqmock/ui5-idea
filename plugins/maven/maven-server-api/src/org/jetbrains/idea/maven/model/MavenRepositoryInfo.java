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
package org.jetbrains.idea.maven.model;

import java.io.Serializable;

public class MavenRepositoryInfo implements Serializable {
  private final String myId;
  private final String myName;
  private final String myUrl;

  public MavenRepositoryInfo(String id, String name, String url) {
    myId = id;
    myName = name;
    myUrl = url;
  }

  public String getId() {
    return myId;
  }

  public String getName() {
    return myName;
  }

  public String getUrl() {
    return myUrl;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();

    MavenId.append(builder, myId);
    MavenId.append(builder, myName);
    MavenId.append(builder, myUrl);

    return builder.toString();
  }
}
