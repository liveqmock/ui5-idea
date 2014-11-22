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
package com.intellij.ide.plugins;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.updateSettings.impl.PluginDownloader;
import com.intellij.openapi.updateSettings.impl.UpdateChecker;
import com.intellij.openapi.updateSettings.impl.UpdateSettings;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.BooleanTableCellEditor;
import com.intellij.ui.BooleanTableCellRenderer;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.hash.HashSet;
import com.intellij.util.ui.ColumnInfo;
import gnu.trove.THashMap;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: stathik
 * Date: Dec 26, 2003
 * Time: 3:51:58 PM
 * To change this template use Options | File Templates.
 */
public class InstalledPluginsTableModel extends PluginTableModel {
  public static Map<PluginId, Integer> NewVersions2Plugins = new HashMap<PluginId, Integer>();
  public static Set<PluginId> updatedPlugins = new HashSet<PluginId>();
  private final Map<PluginId, Boolean> myEnabled = new HashMap<PluginId, Boolean>();
  private final Map<PluginId, Set<PluginId>> myDependentToRequiredListMap = new HashMap<PluginId, Set<PluginId>>();

  private static final String ENABLED_DISABLED = "All plugins";
  private static final String ENABLED = "Enabled";
  private static final String DISABLED = "Disabled";
  private static final String BUNDLED = "Bundled";
  private static final String CUSTOM = "Custom";
  public static final String[] ENABLED_VALUES = new String[] {ENABLED_DISABLED, ENABLED, DISABLED, BUNDLED, CUSTOM};
  private String myEnabledFilter = ENABLED_DISABLED;

  private final Map<String, String> myPlugin2host = new HashMap<String, String>();
  private static final Set<IdeaPluginDescriptor> myInstalled = new HashSet<IdeaPluginDescriptor>();


  public InstalledPluginsTableModel() {
    final MyPluginManagerColumnInfo infoColumn = new MyPluginManagerColumnInfo();
    final EnabledPluginInfo enabledColumn = new EnabledPluginInfo();
    final Spacer spacer = new Spacer();
    columns = SystemInfo.isMac ? new ColumnInfo[]{infoColumn, enabledColumn, spacer}
                    :new ColumnInfo[]{infoColumn, enabledColumn};
    view = new ArrayList<IdeaPluginDescriptor>(Arrays.asList(PluginManagerCore.getPlugins()));
    view.addAll(myInstalled);
    reset(view);

    for (Iterator<IdeaPluginDescriptor> iterator = view.iterator(); iterator.hasNext(); ) {
      @NonNls final String s = iterator.next().getPluginId().getIdString();
      if ("com.intellij".equals(s)) iterator.remove();
    }
    setSortKey(new RowSorter.SortKey(getNameColumn(), SortOrder.ASCENDING));
  }

  public boolean hasProblematicDependencies(PluginId pluginId) {
    final Set<PluginId> ids = myDependentToRequiredListMap.get(pluginId);
    return ids != null && !ids.isEmpty();
  }

  @Nullable
  public Set<PluginId> getRequiredPlugins(PluginId pluginId) {
    return myDependentToRequiredListMap.get(pluginId);
  }

  public boolean isLoaded(PluginId pluginId) {
    return myEnabled.get(pluginId) != null;
  }

  public boolean appendOrUpdateDescriptor(IdeaPluginDescriptor descriptor) {
    final PluginId descrId = descriptor.getPluginId();
    final IdeaPluginDescriptor existing = PluginManager.getPlugin(descrId);
    if (existing != null) {
      updateExistingPlugin(descriptor, existing);
      return true;
    } else if (!myInstalled.contains(descriptor)) {
      myInstalled.add(descriptor);
      view.add(descriptor);
      setEnabled(descriptor, true);
      fireTableDataChanged();
      return true;
    }
    return false;
  }

  public static void updateExistingPlugin(IdeaPluginDescriptor descriptor, @Nullable IdeaPluginDescriptor existing) {
    if (existing != null) {
      updateExistingPluginInfo(descriptor, existing);
      updatedPlugins.add(existing.getPluginId());
    }
  }

  public String getPluginHostUrl(String idString) {
    return myPlugin2host.get(idString);
  }

  public static int getCheckboxColumn() {
    return 1;
  }

  @Override
  public int getNameColumn() {
    return 0;
  }

  private void reset(@NotNull List<IdeaPluginDescriptor> list) {
    for (IdeaPluginDescriptor ideaPluginDescriptor : list) {
      setEnabled(ideaPluginDescriptor);
    }

    updatePluginDependencies();

    SwingUtilities.invokeLater(new Runnable() {
      @Override
      public void run() {
        if (!ApplicationManager.getApplication().isDisposed()) {
          ProgressManager.getInstance().run(new Task.Backgroundable(null, "Load custom plugin repositories data...") {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
              updateRepositoryPlugins(indicator);
            }
          });
        }
      }
    });
  }

  private void updateRepositoryPlugins(@NotNull ProgressIndicator indicator) {
    myPlugin2host.clear();
    List<String> pluginHosts = UpdateSettings.getInstance().getPluginHosts();
    ContainerUtil.addIfNotNull(ApplicationInfoEx.getInstanceEx().getBuiltinPluginsUrl(), pluginHosts);
    for (String host : pluginHosts) {
      try {
        Map<PluginId, PluginDownloader> downloaded = new THashMap<PluginId, PluginDownloader>();
        UpdateChecker.checkPluginsHost(host, downloaded, false, indicator);
        for (PluginId pluginId : downloaded.keySet()) {
          myPlugin2host.put(pluginId.getIdString(), host);
        }
      }
      catch (Exception ignored) {
      }
    }
  }

  private void setEnabled(IdeaPluginDescriptor ideaPluginDescriptor) {
    setEnabled(ideaPluginDescriptor, ideaPluginDescriptor.isEnabled());
  }

  private void setEnabled(IdeaPluginDescriptor ideaPluginDescriptor,
                          final boolean enabled) {
    final Collection<String> disabledPlugins = PluginManagerCore.getDisabledPlugins();
    final PluginId pluginId = ideaPluginDescriptor.getPluginId();
    if (!enabled && !disabledPlugins.contains(pluginId.toString())) {
      myEnabled.put(pluginId, null);
    } else {
      myEnabled.put(pluginId, enabled);
    }
  }

  public Map<PluginId, Set<PluginId>> getDependentToRequiredListMap() {
    return myDependentToRequiredListMap;
  }

  protected void updatePluginDependencies() {
    myDependentToRequiredListMap.clear();

    final int rowCount = getRowCount();
    for (int i = 0; i < rowCount; i++) {
      final IdeaPluginDescriptor descriptor = getObjectAt(i);
      final PluginId pluginId = descriptor.getPluginId();
      myDependentToRequiredListMap.remove(pluginId);
      if (descriptor instanceof IdeaPluginDescriptorImpl && ((IdeaPluginDescriptorImpl)descriptor).isDeleted()) continue;
      final Boolean enabled = myEnabled.get(pluginId);
      if (enabled == null || enabled.booleanValue()) {
        PluginManagerCore.checkDependants(descriptor, new Function<PluginId, IdeaPluginDescriptor>() {
                                            @Override
                                            @Nullable
                                            public IdeaPluginDescriptor fun(final PluginId pluginId) {
                                              return PluginManager.getPlugin(pluginId);
                                            }
                                          }, new Condition<PluginId>() {
                                            @Override
                                            public boolean value(final PluginId dependantPluginId) {
                                              final Boolean enabled = myEnabled.get(dependantPluginId);
                                              if ((enabled == null && !updatedPlugins.contains(dependantPluginId)) ||
                                                  (enabled != null && !enabled.booleanValue())) {
                                                Set<PluginId> required = myDependentToRequiredListMap.get(pluginId);
                                                if (required == null) {
                                                  required = new HashSet<PluginId>();
                                                  myDependentToRequiredListMap.put(pluginId, required);
                                                }

                                                required.add(dependantPluginId);
                                                //return false;
                                              }

                                              return true;
                                            }
                                          }
        );
        if (enabled == null && !myDependentToRequiredListMap.containsKey(pluginId) && !PluginManagerCore.isIncompatible(descriptor)) {
          myEnabled.put(pluginId, true);
        }
      }
    }
  }

  @Override
  public void updatePluginsList(List<IdeaPluginDescriptor> list) {
    //  For each downloadable plugin we need to know whether its counterpart
    //  is already installed, and if yes compare the difference in versions:
    //  availability of newer versions will be indicated separately.
    for (IdeaPluginDescriptor descr : list) {
      PluginId descrId = descr.getPluginId();
      IdeaPluginDescriptor existing = PluginManager.getPlugin(descrId);
      if (existing != null) {
        if (descr instanceof PluginNode) {
          updateExistingPluginInfo(descr, existing);
        } else {
          view.add(descr);
          setEnabled(descr);
        }
      }
    }
    for (IdeaPluginDescriptor descriptor : myInstalled) {
      if (!view.contains(descriptor)) {
        view.add(descriptor);
      }
    }
    fireTableDataChanged();
  }

  @Override
  protected ArrayList<IdeaPluginDescriptor> toProcess() {
    ArrayList<IdeaPluginDescriptor> toProcess = super.toProcess();
    for (IdeaPluginDescriptor descriptor : myInstalled) {
      if (!toProcess.contains(descriptor)) {
        toProcess.add(descriptor);
      }
    }
    return toProcess;
  }

  @Override
  public void filter(final List<IdeaPluginDescriptor> filtered) {
    view.clear();
    for (IdeaPluginDescriptor descriptor : filtered) {
      view.add(descriptor);
    }

    super.filter(filtered);
  }

  private static void updateExistingPluginInfo(IdeaPluginDescriptor descr, IdeaPluginDescriptor existing) {
    int state = PluginDownloader.compareVersionsSkipBroken(existing, descr.getVersion());
    final PluginId pluginId = existing.getPluginId();
    final String idString = pluginId.getIdString();
    if (!((IdeaPluginDescriptorImpl)existing).isDeleted()){
      PluginManagerUISettings.getInstance().getInstalledPlugins().add(idString);
    }
    final UpdateSettings updateSettings = UpdateSettings.getInstance();
    if (state > 0 && !PluginManagerCore.isIncompatible(descr) && !updatedPlugins.contains(descr.getPluginId())) {
      NewVersions2Plugins.put(pluginId, 1);
      if (!updateSettings.myOutdatedPlugins.contains(idString)) {
        updateSettings.myOutdatedPlugins.add(idString);
      }

      final IdeaPluginDescriptorImpl plugin = (IdeaPluginDescriptorImpl)existing;
      plugin.setDownloadsCount(descr.getDownloads());
      plugin.setVendor(descr.getVendor());
      plugin.setVendorEmail(descr.getVendorEmail());
      plugin.setVendorUrl(descr.getVendorUrl());
      plugin.setUrl(descr.getUrl());

    } else {
      updateSettings.myOutdatedPlugins.remove(idString);
      if (NewVersions2Plugins.remove(pluginId) != null) {
        updatedPlugins.add(pluginId);
      }
    }
  }

  public void enableRows(IdeaPluginDescriptor[] ideaPluginDescriptors, Boolean value) {
    for (IdeaPluginDescriptor ideaPluginDescriptor : ideaPluginDescriptors) {
      final PluginId currentPluginId = ideaPluginDescriptor.getPluginId();
      final Boolean enabled = myEnabled.get(currentPluginId) == null ? Boolean.FALSE : value;
      myEnabled.put(currentPluginId, enabled);
    }
    updatePluginDependencies();
    warnAboutMissedDependencies(value, ideaPluginDescriptors);
    hideNotApplicablePlugins(value, ideaPluginDescriptors);
  }

  private void hideNotApplicablePlugins(Boolean value, final IdeaPluginDescriptor... ideaPluginDescriptors) {
    if (!value && ENABLED.equals(myEnabledFilter) || (value && DISABLED.equals(myEnabledFilter))) {
      SwingUtilities.invokeLater(new Runnable() {
        @Override
        public void run() {
          for (IdeaPluginDescriptor ideaPluginDescriptor : ideaPluginDescriptors) {
            view.remove(ideaPluginDescriptor);
            filtered.add(ideaPluginDescriptor);
          }
          fireTableDataChanged();
        }
      });
    }
  }


  public static boolean hasNewerVersion(PluginId descr) {
    return !wasUpdated(descr) &&
           (NewVersions2Plugins.containsKey(descr) ||
            UpdateSettings.getInstance().myOutdatedPlugins.contains(descr.getIdString()));
  }

  public static boolean wasUpdated(PluginId descr) {
    return updatedPlugins.contains(descr);
  }

  public boolean isEnabled(final PluginId pluginId) {
    final Boolean enabled = myEnabled.get(pluginId);
    return enabled != null && enabled.booleanValue();
  }

  public boolean isDisabled(final PluginId pluginId) {
    final Boolean enabled = myEnabled.get(pluginId);
    return enabled != null && !enabled.booleanValue();
  }

  public Map<PluginId, Boolean> getEnabledMap() {
    return myEnabled;
  }

  public String getEnabledFilter() {
    return myEnabledFilter;
  }

  public void setEnabledFilter(String enabledFilter, String filter) {
    myEnabledFilter = enabledFilter;
    filter(filter);
  }

  @Override
  public boolean isPluginDescriptorAccepted(IdeaPluginDescriptor descriptor) {
    if (!myEnabledFilter.equals(ENABLED_DISABLED)) {
      final boolean enabled = isEnabled(descriptor.getPluginId());
      if (enabled && myEnabledFilter.equals(DISABLED)) return false;
      if (!enabled && myEnabledFilter.equals(ENABLED)) return false;
      final boolean bundled = descriptor.isBundled();
      if (bundled && myEnabledFilter.equals(CUSTOM)) return false;
      if (!bundled && myEnabledFilter.equals(BUNDLED)) return false;
    }
    return true;
  }

  private class Spacer extends ColumnInfo<IdeaPluginDescriptor, Object> {
    public Spacer() {
      super("");
    }

    @Override
    public Object valueOf(IdeaPluginDescriptor ideaPluginDescriptor) {
      return null;
    }

    @Override
    public boolean isCellEditable(final IdeaPluginDescriptor ideaPluginDescriptor) {
      return false;
    }

    @Nullable
    @Override
    public TableCellRenderer getRenderer(IdeaPluginDescriptor descriptor) {
      return new DefaultTableCellRenderer();
    }

    @Override
    public Class getColumnClass() {
      return Spacer.class;
    }
  }

  private class EnabledPluginInfo extends ColumnInfo<IdeaPluginDescriptor, Boolean> {

    public EnabledPluginInfo() {
      super(/*IdeBundle.message("plugin.manager.enable.column.title")*/"");
    }

    @Override
    public Boolean valueOf(IdeaPluginDescriptor ideaPluginDescriptor) {
      return myEnabled.get(ideaPluginDescriptor.getPluginId());
    }

    @Override
    public boolean isCellEditable(final IdeaPluginDescriptor ideaPluginDescriptor) {
      return true;
    }

    @Override
    public Class getColumnClass() {
      return Boolean.class;
    }

    @Override
    public TableCellEditor getEditor(final IdeaPluginDescriptor o) {
      return new BooleanTableCellEditor();
    }

    @Override
    public TableCellRenderer getRenderer(final IdeaPluginDescriptor ideaPluginDescriptor) {
      return new BooleanTableCellRenderer() {
        @Override
        public Component getTableCellRendererComponent(JTable table,
                                                       Object value,
                                                       boolean isSelected,
                                                       boolean hasFocus,
                                                       int row,
                                                       int column) {
          return super.getTableCellRendererComponent(table, value == null ? Boolean.TRUE : value, isSelected, hasFocus, row, column);
        }
      };
    }

    @Override
    public void setValue(final IdeaPluginDescriptor ideaPluginDescriptor, Boolean value) {
      final PluginId currentPluginId = ideaPluginDescriptor.getPluginId();
      final Boolean enabled = myEnabled.get(currentPluginId) == null ? Boolean.FALSE : value;
      myEnabled.put(currentPluginId, enabled);
      updatePluginDependencies();
      warnAboutMissedDependencies(enabled, ideaPluginDescriptor);
      hideNotApplicablePlugins(value, ideaPluginDescriptor);
    }

    @Override
    public Comparator<IdeaPluginDescriptor> getComparator() {
      return new Comparator<IdeaPluginDescriptor>() {
        @Override
        public int compare(final IdeaPluginDescriptor o1, final IdeaPluginDescriptor o2) {
          final Boolean enabled1 = myEnabled.get(o1.getPluginId());
          final Boolean enabled2 = myEnabled.get(o2.getPluginId());
          if (enabled1 != null && enabled1.booleanValue()) {
            if (enabled2 != null && enabled2.booleanValue()) {
              return 0;
            }

            return 1;
          }
          else {
            if (enabled2 == null || !enabled2.booleanValue()) {
              return 0;
            }
            return -1;
          }
        }
      };
    }

    @Override
    public int getWidth(JTable table) {
      return new JCheckBox().getPreferredSize().width;
    }
  }

  private void warnAboutMissedDependencies(final Boolean newVal, final IdeaPluginDescriptor... ideaPluginDescriptors) {
    final Set<PluginId> deps = new HashSet<PluginId>();
    final List<IdeaPluginDescriptor> descriptorsToCheckDependencies = new ArrayList<IdeaPluginDescriptor>();
    if (newVal) {
      Collections.addAll(descriptorsToCheckDependencies, ideaPluginDescriptors);
    } else {
      descriptorsToCheckDependencies.addAll(getAllPlugins());
      descriptorsToCheckDependencies.removeAll(Arrays.asList(ideaPluginDescriptors));

      for (Iterator<IdeaPluginDescriptor> iterator = descriptorsToCheckDependencies.iterator(); iterator.hasNext(); ) {
        IdeaPluginDescriptor descriptor = iterator.next();
        final Boolean enabled = myEnabled.get(descriptor.getPluginId());
        if (enabled == null || !enabled.booleanValue()) {
          iterator.remove();
        }
      }
    }

    for (final IdeaPluginDescriptor ideaPluginDescriptor : descriptorsToCheckDependencies) {
      PluginManagerCore.checkDependants(ideaPluginDescriptor, new Function<PluginId, IdeaPluginDescriptor>() {
                                          @Override
                                          @Nullable
                                          public IdeaPluginDescriptor fun(final PluginId pluginId) {
                                            return PluginManager.getPlugin(pluginId);
                                          }
                                        }, new Condition<PluginId>() {
                                          @Override
                                          public boolean value(final PluginId pluginId) {
                                            Boolean enabled = myEnabled.get(pluginId);
                                            if (enabled == null) {
                                              return false;
                                            }
                                            if (newVal && !enabled.booleanValue()) {
                                              deps.add(pluginId);
                                            }

                                            if (!newVal) {
                                              if (ideaPluginDescriptor instanceof IdeaPluginDescriptorImpl &&
                                                  ((IdeaPluginDescriptorImpl)ideaPluginDescriptor).isDeleted()) {
                                                return true;
                                              }
                                              final PluginId pluginDescriptorId = ideaPluginDescriptor.getPluginId();
                                              for (IdeaPluginDescriptor descriptor : ideaPluginDescriptors) {
                                                if (pluginId.equals(descriptor.getPluginId())) {
                                                  deps.add(pluginDescriptorId);
                                                  break;
                                                }
                                              }
                                            }
                                            return true;
                                          }
                                        }
      );
    }
    if (!deps.isEmpty()) {
      final String listOfSelectedPlugins = StringUtil.join(ideaPluginDescriptors, new Function<IdeaPluginDescriptor, String>() {
        @Override
        public String fun(IdeaPluginDescriptor pluginDescriptor) {
          return pluginDescriptor.getName();
        }
      }, ", ");
      final Set<IdeaPluginDescriptor> pluginDependencies = new HashSet<IdeaPluginDescriptor>();
      final String listOfDependencies = StringUtil.join(deps, new Function<PluginId, String>() {
        @Override
        public String fun(final PluginId pluginId) {
          final IdeaPluginDescriptor pluginDescriptor = PluginManager.getPlugin(pluginId);
          assert pluginDescriptor != null;
          pluginDependencies.add(pluginDescriptor);
          return pluginDescriptor.getName();
        }
      }, "<br>");
      final String message = !newVal ? "<html>The following plugins <br>" + listOfDependencies + "<br>are enabled and depend" +(deps.size() == 1 ? "s" : "") + " on selected plugins. " +
                                       "<br>Would you like to disable them too?</html>"
                                     : "<html>The following plugins on which " + listOfSelectedPlugins + " depend" + (ideaPluginDescriptors.length == 1 ? "s" : "") +
                                       " are disabled:<br>" + listOfDependencies + "<br>Would you like to enable them?</html>";
      if (Messages.showOkCancelDialog(message, newVal ? "Enable Dependant Plugins" : "Disable Plugins with Dependency on this", Messages.getQuestionIcon()) == Messages.OK) {
        for (PluginId pluginId : deps) {
          myEnabled.put(pluginId, newVal);
        }

        updatePluginDependencies();
        hideNotApplicablePlugins(newVal, pluginDependencies.toArray(new IdeaPluginDescriptor[pluginDependencies.size()]));
      }
    }
  }

  private class MyPluginManagerColumnInfo extends PluginManagerColumnInfo {
    public MyPluginManagerColumnInfo() {
      super(PluginManagerColumnInfo.COLUMN_NAME, InstalledPluginsTableModel.this);
    }

    @Override
    public TableCellRenderer getRenderer(final IdeaPluginDescriptor pluginDescriptor) {
      return new PluginsTableRenderer(pluginDescriptor, false);
    }

    @Override
    protected boolean isSortByName() {
      return true;
    }

    @Override
    public Comparator<IdeaPluginDescriptor> getComparator() {
      final Comparator<IdeaPluginDescriptor> comparator = getColumnComparator();
      return new Comparator<IdeaPluginDescriptor>() {
        @Override
        public int compare(IdeaPluginDescriptor o1, IdeaPluginDescriptor o2) {
          if (isSortByStatus()) {
            final boolean incompatible1 = PluginManagerCore.isIncompatible(o1);
            final boolean incompatible2 = PluginManagerCore.isIncompatible(o2);
            if (incompatible1) {
              if (incompatible2) return comparator.compare(o1, o2);
              return -1;
            }
            if (incompatible2) return 1;

            final boolean hasNewerVersion1 = hasNewerVersion(o1.getPluginId());
            final boolean hasNewerVersion2 = hasNewerVersion(o2.getPluginId());
            if (hasNewerVersion1) {
              if (hasNewerVersion2) return comparator.compare(o1, o2);
              return -1;
            }
            if (hasNewerVersion2) return 1;


            final boolean wasUpdated1 = wasUpdated(o1.getPluginId());
            final boolean wasUpdated2 = wasUpdated(o2.getPluginId());
            if (wasUpdated1) {
              if (wasUpdated2) return comparator.compare(o1, o2);
              return -1;
            }
            if (wasUpdated2) return 1;


            if (o1 instanceof PluginNode) {
              if (o2 instanceof PluginNode) return comparator.compare(o1, o2);
              return -1;
            }
            if (o2 instanceof PluginNode) return 1;


            final boolean deleted1 = o1 instanceof IdeaPluginDescriptorImpl && ((IdeaPluginDescriptorImpl)o1).isDeleted();
            final boolean deleted2 = o2 instanceof IdeaPluginDescriptorImpl && ((IdeaPluginDescriptorImpl)o2).isDeleted();
            if (deleted1) {
              if (deleted2) return comparator.compare(o1, o2);
              return -1;
            }
            if (deleted2) return 1;

            final boolean enabled1 = isEnabled(o1.getPluginId());
            final boolean enabled2 = isEnabled(o2.getPluginId());
            if (enabled1 && !enabled2) return -1;
            if (enabled2 && !enabled1) return 1;
          }
          return comparator.compare(o1, o2);
        }
      };
    }

    @Override
    public int getWidth(JTable table) {
      return super.getWidth(table);
    }
  }
}