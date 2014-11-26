/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package Runner;

import com.intellij.ide.browsers.WebBrowser;
import com.intellij.util.xmlb.annotations.Attribute;
import com.intellij.util.xmlb.annotations.Transient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class UI5RunnerParameters implements Cloneable {
    private String myUrl = "";
    private WebBrowser myNonDefaultBrowser;

    @Attribute("web_path")
    public String getUrl() {
        return myUrl;
    }

    public void setUrl(@NotNull String url) {
        myUrl = url;
    }

    @Transient
    @Nullable
    public WebBrowser getNonDefaultBrowser() {
        return myNonDefaultBrowser;
    }

    public void setNonDefaultBrowser(@Nullable WebBrowser nonDefaultBrowser) {
        myNonDefaultBrowser = nonDefaultBrowser;
    }

    @SuppressWarnings({"CloneDoesntDeclareCloneNotSupportedException"})
    @Override
    protected UI5RunnerParameters clone() {
        try {
            return (UI5RunnerParameters) super.clone();
        } catch (CloneNotSupportedException e) {
            //noinspection ConstantConditions
            return null;
        }
    }
}
