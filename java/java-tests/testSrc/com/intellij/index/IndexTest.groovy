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
package com.intellij.index
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.impl.CurrentEditorProvider
import com.intellij.openapi.command.impl.UndoManagerImpl
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.Factory
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VirtualFileSystemEntry
import com.intellij.psi.*
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase
import com.intellij.util.indexing.MapIndexStorage
import com.intellij.util.indexing.StorageException
import com.intellij.util.io.*
import org.jetbrains.annotations.NotNull
/**
 * @author Eugene Zhuravlev
 *         Date: Dec 12, 2007
 */
public class IndexTest extends JavaCodeInsightFixtureTestCase {
  @Override
  protected void invokeTestRunnable(@NotNull Runnable runnable) throws Exception {
    if ("testUndoToFileContentForUnsavedCommittedDocument".equals(getName())) {
      super.invokeTestRunnable(runnable);
    } else {
      WriteCommandAction.runWriteCommandAction(getProject(), runnable);
    }
  }

  public void testUpdate() throws StorageException, IOException {
    final File storageFile = FileUtil.createTempFile("indextest", "storage");
    final File metaIndexFile = FileUtil.createTempFile("indextest_inputs", "storage");
    final MapIndexStorage indexStorage = new MapIndexStorage(storageFile, new EnumeratorStringDescriptor(), new EnumeratorStringDescriptor(), 16 * 1024);
    final StringIndex index = new StringIndex(indexStorage, new Factory<PersistentHashMap<Integer, Collection<String>>>() {
      @Override
      public PersistentHashMap<Integer, Collection<String>> create() {
        try {
          return createMetaIndex(metaIndexFile);
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    });

    try {
// build index
      index.update("com/ppp/a.java", "a b c d", null);
      index.update("com/ppp/b.java", "a b g h", null);
      index.update("com/ppp/c.java", "a z f", null);
      index.update("com/ppp/d.java", "a a u y z", null);
      index.update("com/ppp/e.java", "a n chj e c d", null);

      assertDataEquals(index.getFilesByWord("a"), "com/ppp/a.java", "com/ppp/b.java", "com/ppp/c.java", "com/ppp/d.java", "com/ppp/e.java");
      assertDataEquals(index.getFilesByWord("b"), "com/ppp/a.java", "com/ppp/b.java");
      assertDataEquals(index.getFilesByWord("c"), "com/ppp/a.java", "com/ppp/e.java");
      assertDataEquals(index.getFilesByWord("d"), "com/ppp/a.java", "com/ppp/e.java");
      assertDataEquals(index.getFilesByWord("g"), "com/ppp/b.java");
      assertDataEquals(index.getFilesByWord("h"), "com/ppp/b.java");
      assertDataEquals(index.getFilesByWord("z"), "com/ppp/c.java", "com/ppp/d.java");
      assertDataEquals(index.getFilesByWord("f"), "com/ppp/c.java");
      assertDataEquals(index.getFilesByWord("u"), "com/ppp/d.java");
      assertDataEquals(index.getFilesByWord("y"), "com/ppp/d.java");
      assertDataEquals(index.getFilesByWord("n"), "com/ppp/e.java");
      assertDataEquals(index.getFilesByWord("chj"), "com/ppp/e.java");
      assertDataEquals(index.getFilesByWord("e"), "com/ppp/e.java");

      // update index

      index.update("com/ppp/d.java", "a u y z", "a a u y z");
      assertDataEquals(index.getFilesByWord("a"), "com/ppp/a.java", "com/ppp/b.java", "com/ppp/c.java", "com/ppp/d.java", "com/ppp/e.java");
      index.update("com/ppp/d.java", "u y z", "a u y z");
      assertDataEquals(index.getFilesByWord("a"), "com/ppp/a.java", "com/ppp/b.java", "com/ppp/c.java", "com/ppp/e.java");
      index.update("com/ppp/d.java", "a a a u y z", "u y z");
      assertDataEquals(index.getFilesByWord("a"), "com/ppp/a.java", "com/ppp/b.java", "com/ppp/c.java", "com/ppp/d.java", "com/ppp/e.java");

      index.update("com/ppp/e.java", "a n chj e c d z", "a n chj e c d");
      assertDataEquals(index.getFilesByWord("z"), "com/ppp/c.java", "com/ppp/d.java", "com/ppp/e.java");

      index.update("com/ppp/b.java", null, "a b g h");
      assertDataEquals(index.getFilesByWord("a"), "com/ppp/a.java", "com/ppp/c.java", "com/ppp/d.java", "com/ppp/e.java");
      assertDataEquals(index.getFilesByWord("b"), "com/ppp/a.java");
      assertDataEquals(index.getFilesByWord("g"));
      assertDataEquals(index.getFilesByWord("h"));
    }
    finally {
      indexStorage.close();
      FileUtil.delete(storageFile);
    }
  }

  private static PersistentHashMap<Integer, Collection<String>> createMetaIndex(File metaIndexFile) throws IOException {
    return new PersistentHashMap<Integer, Collection<String>>(metaIndexFile, new EnumeratorIntegerDescriptor(), new DataExternalizer<Collection<String>>() {
      @Override
      public void save(@NotNull DataOutput out, Collection<String> value) throws IOException {
        DataInputOutputUtil.writeINT(out, value.size());
        for (String key : value) {
          out.writeUTF(key);
        }
      }

      @Override
      public Collection<String> read(@NotNull DataInput _in) throws IOException {
        final int size = DataInputOutputUtil.readINT(_in);
        final List<String> list = new ArrayList<String>();
        for (int idx = 0; idx < size; idx++) {
          list.add(_in.readUTF());
        }
        return list;
      }
    });
  }

  private static <T> void assertDataEquals(List<T> actual, T... expected) {
    assertTrue(new HashSet<T>(Arrays.asList(expected)).equals(new HashSet<T>(actual)));
  }

  public void testCollectedPsiWithChangedDocument() throws IOException {
    final VirtualFile vFile = myFixture.addClass("class Foo {}").getContainingFile().getVirtualFile();

    assertNotNull(findClass("Foo"));
    PsiFile psiFile = getPsiManager().findFile(vFile);
    assertNotNull(psiFile);

    Document document = FileDocumentManager.getInstance().getDocument(vFile);
    document.deleteString(0, document.getTextLength());
    assertNotNull(findClass("Foo"));

    psiFile = null;
    PlatformTestUtil.tryGcSoftlyReachableObjects();
    assertNull(getPsiManager().getFileManager().getCachedPsiFile(vFile));

    PsiClass foo = findClass("Foo");
    assertNotNull(foo);
    assertTrue(foo.isValid());
    assertEquals("class Foo {}", foo.getText());
    assertTrue(foo.isValid());

    PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
    assertNull(findClass("Foo"));
  }
  
  public void testCollectedPsiWithDocumentChangedCommittedAndChangedAgain() throws IOException {
    final VirtualFile vFile = myFixture.addClass("class Foo {}").getContainingFile().getVirtualFile();

    assertNotNull(findClass("Foo"));
    PsiFile psiFile = getPsiManager().findFile(vFile);
    assertNotNull(psiFile);

    Document document = FileDocumentManager.getInstance().getDocument(vFile);
    document.deleteString(0, document.getTextLength());
    PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
    document.insertString(0, " ");
    //assertNotNull(myJavaFacade.findClass("Foo", scope));

    psiFile = null;
    PlatformTestUtil.tryGcSoftlyReachableObjects();
    assertNull(getPsiManager().getFileManager().getCachedPsiFile(vFile));

    PsiClass foo = findClass("Foo");
    assertNull(foo);
  }

  private PsiClass findClass(String name) {
    return JavaPsiFacade.getInstance(getProject()).findClass(name, GlobalSearchScope.allScope(getProject()));
  }

  public void testSavedUncommittedDocument() throws IOException {
    final VirtualFile vFile = myFixture.addFileToProject("Foo.java", "").getVirtualFile();

    assertNull(findClass("Foo"));
    PsiFile psiFile = getPsiManager().findFile(vFile);
    assertNotNull(psiFile);

    long count = getPsiManager().getModificationTracker().getModificationCount();

    Document document = FileDocumentManager.getInstance().getDocument(vFile);
    document.insertString(0, "class Foo {}");
    FileDocumentManager.getInstance().saveDocument(document);

    assertTrue(count == getPsiManager().getModificationTracker().getModificationCount());
    assertNull(findClass("Foo"));

    PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
    assertNotNull(findClass("Foo"));
    assertNotNull(findClass("Foo").getText());
    // if Foo exists now, mod count should be different
    assertTrue(count != getPsiManager().getModificationTracker().getModificationCount());
  }

  public void testSkipUnknownFileTypes() throws IOException {
    final VirtualFile vFile = myFixture.addFileToProject("Foo.test", "Foo").getVirtualFile();
    assertEquals(PlainTextFileType.INSTANCE, vFile.getFileType());
    final PsiSearchHelper helper = PsiSearchHelper.SERVICE.getInstance(getProject());
    assertOneElement(helper.findFilesWithPlainTextWords("Foo"));

    final Document document = FileDocumentManager.getInstance().getDocument(vFile);
    //todo should file type be changed silently without events?
    //assertEquals(UnknownFileType.INSTANCE, vFile.getFileType());

    final PsiFile file = PsiDocumentManager.getInstance(getProject()).getPsiFile(document);
    assertInstanceOf(file, PsiPlainTextFile.class);
    assertEquals("Foo", file.getText());

    assertOneElement(helper.findFilesWithPlainTextWords("Foo"));

    WriteCommandAction.runWriteCommandAction(getProject(), new Runnable() {
      @Override
      public void run() {
        document.insertString(0, " ");
        assertEquals("Foo", file.getText());
        assertOneElement(helper.findFilesWithPlainTextWords("Foo"));

        FileDocumentManager.getInstance().saveDocument(document);
        assertEquals("Foo", file.getText());
        assertOneElement(helper.findFilesWithPlainTextWords("Foo"));

        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertEquals(" Foo", file.getText());
        assertOneElement(helper.findFilesWithPlainTextWords("Foo"));
      }
    });
  }

  public void testUndoToFileContentForUnsavedCommittedDocument() throws IOException {
    final VirtualFile vFile = myFixture.addClass("class Foo {}").getContainingFile().getVirtualFile();
    ((VirtualFileSystemEntry)vFile).setModificationStamp(0); // as unchanged file

    final Document document = FileDocumentManager.getInstance().getDocument(vFile);
    assertTrue(document != null && document.getModificationStamp() == 0);
    assertNotNull(findClass("Foo"));

    WriteCommandAction.runWriteCommandAction(getProject(), new Runnable() {
      @Override
      public void run() {
        document.insertString(0, "import Bar;\n");
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertNotNull(findClass("Foo"));
      }
    });

    final UndoManager undoManager = UndoManager.getInstance(getProject());
    final FileEditor selectedEditor = FileEditorManager.getInstance(getProject()).openFile(vFile, false)[0];
    ((UndoManagerImpl)undoManager).setEditorProvider(new CurrentEditorProvider() {
      @Override
      public FileEditor getCurrentEditor() {
        return selectedEditor;
      }
    });

    assertTrue(undoManager.isUndoAvailable(selectedEditor));
    FileDocumentManager.getInstance().saveDocument(document);
    undoManager.undo(selectedEditor);

    assertNotNull(findClass("Foo"));
  }

  public void "test changing a file without psi makes the document committed and updates index"() {
    def psiFile = myFixture.addFileToProject("Foo.java", "class Foo {}")
    def vFile = psiFile.virtualFile
    def scope = GlobalSearchScope.allScope(project)

    FileDocumentManager.instance.getDocument(vFile).text = "import zoo.Zoo; class Foo1 {}"
    assert PsiDocumentManager.getInstance(project).uncommittedDocuments
    psiFile = null

    PlatformTestUtil.tryGcSoftlyReachableObjects()

    assert !((PsiManagerEx) psiManager).fileManager.getCachedPsiFile(vFile)

    FileDocumentManager.instance.saveAllDocuments()

    VfsUtil.saveText(vFile, "class Foo3 {}")

    assert !PsiDocumentManager.getInstance(project).uncommittedDocuments

    assert JavaPsiFacade.getInstance(project).findClass("Foo3", scope)
  }

}
