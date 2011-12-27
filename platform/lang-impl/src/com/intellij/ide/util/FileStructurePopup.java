/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package com.intellij.ide.util;

import com.intellij.ide.IdeBundle;
import com.intellij.ide.commander.CommanderPanel;
import com.intellij.ide.commander.ProjectListBuilder;
import com.intellij.ide.structureView.StructureViewBuilder;
import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder;
import com.intellij.ide.structureView.newStructureView.StructureViewComponent;
import com.intellij.ide.structureView.newStructureView.TreeActionsOwner;
import com.intellij.ide.structureView.newStructureView.TreeModelWrapper;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.ide.util.treeView.smartTree.*;
import com.intellij.lang.LanguageStructureViewBuilder;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.MnemonicHelper;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.ui.ListScrollingUtil;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SpeedSearchBase;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.docking.DockManager;
import com.intellij.ui.speedSearch.ElementFilter;
import com.intellij.ui.speedSearch.SpeedSearchSupply;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.ui.treeStructure.filtered.FilteringTreeBuilder;
import com.intellij.ui.treeStructure.filtered.FilteringTreeStructure;
import com.intellij.util.Alarm;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.HashSet;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * @author Konstantin Bulenkov
 */
public class FileStructurePopup implements Disposable {
  private final Editor myEditor;
  private final Navigatable myNavigatable;
  private final Project myProject;
  private final StructureViewModel myTreeModel;
  private final StructureViewModel myBaseTreeModel;
  @NotNull private final Disposable myDisposable;
  private final MyTreeActionsOwner myTreeActionsOwner;
  private JBPopup myPopup;

  @NonNls private static final String ourPropertyKey = "FileStructure.narrowDown";
  private boolean myShouldNarrowDown = false;
  private Tree myTree;
  private FilteringTreeBuilder myAbstractTreeBuilder;
  private String myTitle;
  private TreeSpeedSearch mySpeedSearch;

  public FileStructurePopup(StructureViewModel structureViewModel,
                             @Nullable Editor editor,
                             Project project,
                             Navigatable navigatable,
                             @NotNull final Disposable auxDisposable,
                             final boolean applySortAndFilter) {
    myProject = project;
    myEditor = editor;
    myNavigatable = navigatable;
    myBaseTreeModel = structureViewModel;
    myDisposable = auxDisposable;
    if (applySortAndFilter) {
      myTreeActionsOwner = new MyTreeActionsOwner();
      myTreeModel = new TreeModelWrapper(structureViewModel, myTreeActionsOwner);
    }
    else {
      myTreeActionsOwner = null;
      myTreeModel = structureViewModel;
    }

    PsiFile psiFile = getPsiFile(project);

    final PsiElement psiElement = getCurrentElement(psiFile);

    //myDialog.setUndecorated(true);

    if (psiElement != null) {
      if (structureViewModel.shouldEnterElement(psiElement)) {
        //myCommanderPanel.getBuilder().enterElement(psiElement, PsiUtilBase.getVirtualFile(psiElement));
      }
      else {
        //myCommanderPanel.getBuilder().selectElement(psiElement, PsiUtilBase.getVirtualFile(psiElement));
      }
    }

    SmartTreeStructure smartTreeStructure = new SmartTreeStructure(project, myTreeModel){
      public void rebuildTree() {
        if (!myPopup.isDisposed()) {
          super.rebuildTree();
        }
      }

      public boolean isToBuildChildrenInBackground(final Object element) {
        return getRootElement() == element;
      }

      protected TreeElementWrapper createTree() {
        return new StructureViewComponent.StructureViewTreeElementWrapper(myProject, myModel.getRoot(), myModel);
      }

      @Override
      public String toString() {
        return "structure view tree structure(model=" + myTreeModel + ")";
      }
    };
    ElementFilter filter = new FileStructurePopupFilter();
    final FilteringTreeStructure treeStructure = new FilteringTreeStructure(project, filter, smartTreeStructure);

    //final DefaultTreeModel model = new DefaultTreeModel(new DefaultMutableTreeNode(treeStructure.getRootElement()));
    myTree = new Tree(new DefaultMutableTreeNode(treeStructure.getRootElement()));
    myTree.setRootVisible(false);
    myTree.setShowsRootHandles(true);

    mySpeedSearch = new TreeSpeedSearch(myTree, TreeSpeedSearch.NODE_DESCRIPTOR_TOSTRING, true);
    myAbstractTreeBuilder = new FilteringTreeBuilder(project, myTree, filter, treeStructure, null) {
      @Override
      protected boolean validateNode(Object child) {
        return StructureViewComponent.isValid(child);
      }
    };

  }

  public void show() {
    final JComponent panel = createCenterPanel();
    new MnemonicHelper().register(panel);
    myPopup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, null)
      .setTitle(myTitle)
      .setResizable(true)
      .setFocusable(true)
      .setMovable(true)
      .setCancelKeyEnabled(false)
      .setDimensionServiceKey(null, getDimensionServiceKey(), false)
      .createPopup();
    Disposer.register(myPopup, myDisposable);
    Disposer.register(myPopup, this);
    Disposer.register(myPopup, myAbstractTreeBuilder);
    myPopup.showInCenterOf(myEditor.getContentComponent());
    myAbstractTreeBuilder.expandAll(new Runnable() {
      @Override
      public void run() {
        IdeFocusManager.getInstance(myProject).requestFocus(myTree, true);
        myAbstractTreeBuilder.queueUpdate().doWhenDone(new Runnable() {
          @Override
          public void run() {
            myAbstractTreeBuilder.expandAll(null);
          }
        });
      }
    });    
    final Alarm alarm = new Alarm(myPopup);
    alarm.addRequest(new Runnable() {
      String filter = "";
      @Override
      public void run() {
        String prefix = mySpeedSearch.getEnteredPrefix();
        if (prefix == null) prefix = "";
        
        if (!filter.equals(prefix)) {
          filter = prefix;
          myAbstractTreeBuilder.refilter();
        }
        alarm.addRequest(this, 300);
      }
    }, 300);
  }
  
  @Nullable
  protected PsiFile getPsiFile(final Project project) {
    return PsiDocumentManager.getInstance(project).getPsiFile(myEditor.getDocument());
  }

  @Nullable
  protected Border createContentPaneBorder() {
    return null;
  }

  public void dispose() {
  }

  protected String getDimensionServiceKey() {
    return DockManager.getInstance(myProject).getDimensionKeyForFocus("#com.intellij.ide.util.FileStructureDialog");
  }

  @Nullable
  protected PsiElement getCurrentElement(@Nullable final PsiFile psiFile) {
    if (psiFile == null) return null;

    PsiDocumentManager.getInstance(myProject).commitAllDocuments();

    Object elementAtCursor = myTreeModel.getCurrentEditorElement();
    if (elementAtCursor instanceof PsiElement) {
      return (PsiElement)elementAtCursor;
    }

    return null;
  }

  protected JComponent createCenterPanel() {
    List<FileStructureFilter> fileStructureFilters = new ArrayList<FileStructureFilter>();
    List<FileStructureNodeProvider> fileStructureNodeProviders = new ArrayList<FileStructureNodeProvider>();
    if (myTreeActionsOwner != null) {
      for(Filter filter: myBaseTreeModel.getFilters()) {
        if (filter instanceof FileStructureFilter) {
          final FileStructureFilter fsFilter = (FileStructureFilter)filter;
          myTreeActionsOwner.setActionIncluded(fsFilter, true);
          fileStructureFilters.add(fsFilter);
        }
      }

      if (myBaseTreeModel instanceof ProvidingTreeModel) {
        for (NodeProvider provider : ((ProvidingTreeModel)myBaseTreeModel).getNodeProviders()) {
          if (provider instanceof FileStructureNodeProvider) {
            fileStructureNodeProviders.add((FileStructureNodeProvider)provider);
          }
        }
      }
    }
    final JPanel panel = new JPanel(new BorderLayout());
    JPanel comboPanel = new JPanel(new GridLayout(0, 2, 0, 0));

    final Shortcut[] F4 = ActionManager.getInstance().getAction(IdeActions.ACTION_EDIT_SOURCE).getShortcutSet().getShortcuts();
    final Shortcut[] ENTER = CustomShortcutSet.fromString("ENTER").getShortcuts();
    final CustomShortcutSet shortcutSet = new CustomShortcutSet(ArrayUtil.mergeArrays(F4, ENTER));
    new AnAction() {
      public void actionPerformed(AnActionEvent e) {
        final boolean succeeded = navigateSelectedElement();
        if (succeeded) {
          unregisterCustomShortcutSet(panel);
        }
      }
    }.registerCustomShortcutSet(shortcutSet, panel);

    new AnAction() {
      public void actionPerformed(AnActionEvent e) {
        if (mySpeedSearch != null && mySpeedSearch.isPopupActive()) {
          mySpeedSearch.hidePopup();
        } else {
          myPopup.cancel();
        }
      }
    }.registerCustomShortcutSet(CustomShortcutSet.fromString("ESCAPE"), myTree);

    //myCommanderPanel.setPreferredSize(new Dimension(400, 500));

    addNarrowDownCheckbox(comboPanel);

    for(FileStructureFilter filter: fileStructureFilters) {
      addCheckbox(comboPanel, filter);
    }

    for (FileStructureNodeProvider provider : fileStructureNodeProviders) {
      addCheckbox(comboPanel, provider);
    }

    panel.add(comboPanel, BorderLayout.NORTH);
    panel.add(ScrollPaneFactory.createScrollPane(myAbstractTreeBuilder.getTree()), BorderLayout.CENTER);

    return panel;
  }
  
  @Nullable
  private AbstractTreeNode getSelectedNode() {
    Object component = myTree.getSelectionPath().getLastPathComponent();
    if (component instanceof DefaultMutableTreeNode) {
      component = ((DefaultMutableTreeNode)component).getUserObject();
      if (component instanceof FilteringTreeStructure.Node) {
        component = ((FilteringTreeStructure.Node)component).getDelegate();
        if (component instanceof FilteringTreeStructure.Node) {
          component = ((FilteringTreeStructure.Node)component).getDelegate();
          if (component instanceof AbstractTreeNode) {
            return (AbstractTreeNode)component;
          }
        }
      }
    }
    return null;
  }

  public boolean navigateSelectedElement() {
    final Ref<Boolean> succeeded = new Ref<Boolean>();
    final CommandProcessor commandProcessor = CommandProcessor.getInstance();
    commandProcessor.executeCommand(myProject, new Runnable() {
      public void run() {
        final AbstractTreeNode selectedNode = getSelectedNode();
        if (selectedNode != null) {
          if (selectedNode.canNavigateToSource()) {
            selectedNode.navigate(true);
            succeeded.set(true);
          } else {
            succeeded.set(false);
          }
        } else {
          succeeded.set(false);
        }


        IdeDocumentHistory.getInstance(myProject).includeCurrentCommandAsNavigation();
      }
    }, "Navigate", null);
    if (succeeded.get()) {
      myPopup.cancel();
    }
    return succeeded.get();
  }

  protected boolean isShowRoot(final PsiFile psiFile) {
    StructureViewBuilder viewBuilder = LanguageStructureViewBuilder.INSTANCE.getStructureViewBuilder(psiFile);
    return viewBuilder instanceof TreeBasedStructureViewBuilder && ((TreeBasedStructureViewBuilder)viewBuilder).isRootNodeShown();
  }

  private void addNarrowDownCheckbox(final JPanel panel) {
    final JCheckBox checkBox = new JCheckBox(IdeBundle.message("checkbox.narrow.down.the.list.on.typing"));
    checkBox.setSelected(PropertiesComponent.getInstance().isTrueValue(ourPropertyKey));
    checkBox.addChangeListener(new ChangeListener() {
      public void stateChanged(ChangeEvent e) {
        myShouldNarrowDown = checkBox.isSelected();
        PropertiesComponent.getInstance().setValue(ourPropertyKey, Boolean.toString(myShouldNarrowDown));

        myAbstractTreeBuilder.queueUpdate();
      }
    });

    checkBox.setFocusable(false);
    panel.add(checkBox);
    //,new GridBagConstraints(0, GridBagConstraints.RELATIVE, 1, 1, 0, 0, GridBagConstraints.WEST, GridBagConstraints.NONE, new Insets(0, 5, 0, 5), 0, 0));
  }

  private void addCheckbox(final JPanel panel, final TreeAction action) {
    String text = action instanceof FileStructureFilter ? ((FileStructureFilter)action).getCheckBoxText() :
                  action instanceof FileStructureNodeProvider ? ((FileStructureNodeProvider)action).getCheckBoxText() : null;

    if (text == null) return;

    Shortcut[] shortcuts = action instanceof FileStructureFilter ?
                          ((FileStructureFilter)action).getShortcut() : ((FileStructureNodeProvider)action).getShortcut();


    final JCheckBox chkFilter = new JCheckBox();
    chkFilter.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        PsiElement currentParent = null;
        final AbstractTreeNode parentNode = null;//todo myAbstractTreeBuilder.getUi().getRootNode().;
        final Object value = null; //todo parentNode.getValue();
        if (value instanceof StructureViewTreeElement) {
          final Object elementValue = ((StructureViewTreeElement)value).getValue();
          if (elementValue instanceof PsiElement) {
            currentParent = (PsiElement) elementValue;
          }
        }
        final boolean state = chkFilter.isSelected();
        myTreeActionsOwner.setActionIncluded(action, action instanceof FileStructureFilter ? !state : state);
        myAbstractTreeBuilder.refilter(); //todo full update
        myAbstractTreeBuilder.queueUpdate();
        if (currentParent != null) {
          boolean oldNarrowDown = myShouldNarrowDown;
          myShouldNarrowDown = false;
          try {
            //todo builder.enterElement(currentParent, PsiUtilBase.getVirtualFile(currentParent));
          }
          finally {
            myShouldNarrowDown = oldNarrowDown;
          }
        }
        myAbstractTreeBuilder.queueUpdate();

        if (SpeedSearchBase.hasActiveSpeedSearch(myTree)) {
          final SpeedSearchSupply supply = SpeedSearchSupply.getSupply(myTree);
          if (supply != null && supply.isPopupActive()) supply.refreshSelection();
        }
      }
    });
    chkFilter.setFocusable(false);

    if (shortcuts.length > 0) {
      text += " (" + KeymapUtil.getShortcutText(shortcuts[0]) + ")";
      new AnAction() {
        public void actionPerformed(final AnActionEvent e) {
          chkFilter.doClick();
        }
      }.registerCustomShortcutSet(new CustomShortcutSet(shortcuts), myTree);
    }
    chkFilter.setText(text);
    panel.add(chkFilter);
      //,new GridBagConstraints(0, GridBagConstraints.RELATIVE, 1, 1, 0, 0, GridBagConstraints.WEST, GridBagConstraints.NONE, new Insets(0, 5, 0, 5), 0, 0));
  }

  @Nullable
  protected JComponent createSouthPanel() {
    return null;
  }

  public void setTitle(String title) {
    myTitle = title;
  }

  private class MyCommanderPanel extends CommanderPanel implements DataProvider {
    @Override
    protected boolean shouldDrillDownOnEmptyElement(final AbstractTreeNode node) {
      return false;
    }

    public MyCommanderPanel(Project _project) {
      super(_project, false, true);
      myList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      myListSpeedSearch.addChangeListener(new PropertyChangeListener() {
        public void propertyChange(PropertyChangeEvent evt) {
          ProjectListBuilder builder = (ProjectListBuilder)getBuilder();
          if (builder == null) {
            return;
          }
          builder.addUpdateRequest(hasPrefixShortened(evt));
          ApplicationManager.getApplication().invokeLater(new Runnable() {
            public void run() {
              int index = myList.getSelectedIndex();
              if (index != -1 && index < myList.getModel().getSize()) {
                myList.clearSelection();
                ListScrollingUtil.selectItem(myList, index);
              }
              else {
                ListScrollingUtil.ensureSelectionExists(myList);
              }
            }
          });

          myList.repaint(); // to update match highlighting
        }
      });
      myListSpeedSearch.setComparator(createSpeedSearchComparator());
    }

    private boolean hasPrefixShortened(final PropertyChangeEvent evt) {
      return evt.getNewValue() != null && evt.getOldValue() != null &&
             ((String)evt.getNewValue()).length() < ((String)evt.getOldValue()).length();
    }

    public boolean navigateSelectedElement() {
      final Ref<Boolean> succeeded = new Ref<Boolean>();
      final CommandProcessor commandProcessor = CommandProcessor.getInstance();
      commandProcessor.executeCommand(myProject, new Runnable() {
        public void run() {
          final AbstractTreeNode selectedNode = getSelectedNode();
          if (selectedNode != null) {
            if (selectedNode.canNavigateToSource()) {
              selectedNode.navigate(true);
              succeeded.set(true);
            } else {
              succeeded.set(false);
            }
          } else {
            succeeded.set(false);
          }


          IdeDocumentHistory.getInstance(myProject).includeCurrentCommandAsNavigation();
        }
      }, "Navigate", null);
      if (succeeded.get()) {
        myPopup.cancel();
      }
      return succeeded.get();
    }

    public Object getData(String dataId) {
      Object selectedElement = ContainerUtil.getFirstItem(myAbstractTreeBuilder.getSelectedElements());

      if (selectedElement instanceof TreeElement) selectedElement = ((StructureViewTreeElement)selectedElement).getValue();

      if (PlatformDataKeys.NAVIGATABLE.is(dataId)) {
        return selectedElement instanceof Navigatable ? selectedElement : myNavigatable;
      }

      if (OpenFileDescriptor.NAVIGATE_IN_EDITOR.is(dataId)) return myEditor;

      return getDataImpl(dataId);
    }

    public String getEnteredPrefix() {
      return myListSpeedSearch.getEnteredPrefix();
    }

    public void updateSpeedSearch() {
      myListSpeedSearch.refreshSelection();
    }

    public void scrollSelectionInView() {
      int selectedIndex = myList.getSelectedIndex();
      if (selectedIndex >= 0) {
        ListScrollingUtil.ensureIndexIsVisible(myList, selectedIndex, 0);
      }
    }
  }

  private class MyStructureTreeStructure extends SmartTreeStructure {
    public MyStructureTreeStructure(Project project) {
      super(project, myTreeModel);
    }

    public Object[] getChildElements(Object element) {
      Object[] childElements = super.getChildElements(element);

      if (!myShouldNarrowDown) {
        return childElements;
      }

      String enteredPrefix = null;//todo myCommanderPanel.getEnteredPrefix();
      if (enteredPrefix == null) {
        return childElements;
      }

      ArrayList<Object> filteredElements = new ArrayList<Object>(childElements.length);
      SpeedSearchBase.SpeedSearchComparator speedSearchComparator = createSpeedSearchComparator();

      for (Object child : childElements) {
        if (child instanceof AbstractTreeNode) {
          Object value = ((AbstractTreeNode)child).getValue();
          if (value instanceof TreeElement) {
            String name = ((TreeElement)value).getPresentation().getPresentableText();
            if (name == null) {
              continue;
            }
            if (speedSearchComparator.matchingFragments(enteredPrefix, name) == null) {
              continue;
            }
          }
        }
        filteredElements.add(child);
      }
      return ArrayUtil.toObjectArray(filteredElements);
    }

    public void rebuildTree() {
      getChildElements(getRootElement());   // for some reason necessary to rebuild tree correctly
      super.rebuildTree();
    }
  }

  private static SpeedSearchBase.SpeedSearchComparator createSpeedSearchComparator() {
    return new SpeedSearchBase.SpeedSearchComparator(false);
  }

  private class MyTreeActionsOwner implements TreeActionsOwner {
    private final Set<TreeAction> myActions = new HashSet<TreeAction>();

    public void setActionActive(String name, boolean state) {
    }

    public boolean isActionActive(String name) {
      for (final Sorter sorter : myBaseTreeModel.getSorters()) {
        if (sorter.getName().equals(name)) {
          if (!sorter.isVisible()) return true;
        }
      }
      for(TreeAction action: myActions) {
        if (action.getName().equals(name)) return true;
      }
      return Sorter.ALPHA_SORTER_ID.equals(name);
    }

    public void setActionIncluded(final TreeAction filter, final boolean selected) {
      if (selected) {
        myActions.add(filter);
      }
      else {
        myActions.remove(filter);
      }
    }
  }

  private class FileStructurePopupFilter implements ElementFilter {
    private String myLastFilter = null;
    private HashSet<Object> myVisibleParents = new HashSet<Object>();
    @Override
    public boolean shouldBeShowing(Object value) {
      if (!myShouldNarrowDown) return true;

      String filter = mySpeedSearch != null && !StringUtil.isEmpty(mySpeedSearch.getEnteredPrefix()) 
                      ? mySpeedSearch.getEnteredPrefix() : null;
      if (!StringUtil.equals(myLastFilter, filter)) {
        myVisibleParents.clear();
      }
      if (filter != null) {
        if (myVisibleParents.contains(value)) {
          return true;
        }

        final Iterable<TextRange> ranges = mySpeedSearch.matchingFragments(value.toString());
        boolean matches = ranges != null && ranges.iterator().hasNext();
        
        if (matches) {
          Object o = value;
          while ((o = ((FilteringTreeStructure.Node)o).getParent()) != null) {
            myVisibleParents.add(o);
          }
          return true;
        } else {
          return false;
        }
        
      } 
      return true;
    }
  }
}
