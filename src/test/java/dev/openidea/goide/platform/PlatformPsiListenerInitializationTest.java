package dev.openidea.goide.platform;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.ide.impl.OpenProjectTaskBuilder;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.EditorEventMulticaster;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.util.Alarm;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

/** Actual default-loader public platform patch, including interrupted-constructor native commits. */
public final class PlatformPsiListenerInitializationTest extends LightPlatformTestCase {
  private static final String HANDLER = "com.intellij.codeInsight.daemon.impl.PsiChangeHandler";
  private static final String PREFIX = HANDLER.replace('.', '/');
  private static final Set<String> REQUIRED = Set.of(PREFIX + ".class", PREFIX + "$1.class", PREFIX + "$Change.class");

  @Override protected boolean runInDispatchThread() { return false; }

  public void testDefaultLoaderUsesEveryRecordedPatchedClassAndLeavesTheSourceSdkUntouched() throws Exception {
    JsonObject report=report();
    Path jar=Path.of(report.get("patchJar").getAsString()).toRealPath();
    Class<?> handler=Class.forName(HANDLER);
    assertSame("Use the actual core loader, not an isolated copy",ApplicationManager.class.getClassLoader(),handler.getClassLoader());
    assertClassOrigin(handler,PREFIX+".class",jar);
    assertEquals(report.get("patchedJarSha256").getAsString(),sha(jar));
    JsonObject recorded=report.getAsJsonObject("classSha256");
    assertEquals(REQUIRED,recorded.keySet());
    try(ZipFile zip=new ZipFile(jar.toFile())) {
      Set<String> actual=new TreeSet<>();
      zip.stream().map(e -> e.getName()).filter(n -> n.equals(PREFIX+".class") || n.startsWith(PREFIX+"$") && n.endsWith(".class")).forEach(actual::add);
      assertEquals(REQUIRED,actual);
      JsonObject marker=JsonParser.parseString(new String(zip.getInputStream(zip.getEntry(report.get("marker").getAsString())).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
      assertEquals(report.get("patchSha256"),marker.get("patchSha256"));
      assertEquals(recorded,marker.getAsJsonObject("classSha256"));
      for(String name:REQUIRED) {
        Class<?> type=Class.forName(name.substring(0,name.length()-6).replace('/','.'));
        assertSame("Every outer/nested class must share the actual core loader",handler.getClassLoader(),type.getClassLoader());
        assertClassOrigin(type,name,jar);
        try(InputStream bytes=handler.getClassLoader().getResourceAsStream(name)) {
          assertNotNull(bytes);assertEquals(recorded.get(name).getAsString(),sha(bytes));
        }
      }
    }
    Path source=Path.of(report.get("sourceSdk").getAsString()).resolve(report.get("upstreamJarRelativePath").getAsString()).toRealPath();
    assertFalse("The test must use an owned core copy",source.equals(jar));
    assertEquals(report.get("upstreamJarSha256").getAsString(),sha(source));
  }

  public void testInterruptedConstructorCannotPoisonLaterNativeDocumentCommits() throws Exception {
    try(ControlFixture fixture=new ControlFixture()) {
      fixture.construct(true);
      assertEquals(2,fixture.registrations.get());
      fixture.commit("// interrupted construction 中文😀\n");
      fixture.commit("// next native commit still works\n");
      assertTrue(PsiDocumentManager.getInstance(fixture.project).isCommitted(fixture.document));
    }
  }

  public void testNormallyConstructedHandlerStillSchedulesAndFinishesItsNativeCommitUpdate() throws Exception {
    try(ControlFixture fixture=new ControlFixture()) {
      Object handler=fixture.construct(false);
      assertEquals(2,fixture.registrations.get());
      fixture.commit("// normal construction 中文😀\n");
      var field=handler.getClass().getDeclaredField("myUpdateFileStatusAlarm");field.setAccessible(true);
      Alarm alarm=(Alarm)field.get(handler);assertNotNull(alarm);
      alarm.waitForAllExecuted(5,TimeUnit.SECONDS);
      assertTrue(alarm.isEmpty());
      assertTrue(PsiDocumentManager.getInstance(fixture.project).isCommitted(fixture.document));
    }
  }

  private final class ControlFixture implements AutoCloseable {
    final Path root=Files.createTempDirectory("open-goland-psi-listener-空 格😀-").toRealPath();
    final Disposable constructorOwner=Disposer.newDisposable("one controlled PsiChangeHandler constructor");
    final AtomicInteger registrations=new AtomicInteger();
    Project project;
    Document document;

    ControlFixture() throws Exception {
      try {
        VfsRootAccess.allowRootAccess(getTestRootDisposable(),root.toString());
        var options=new OpenProjectTaskBuilder().build(builder -> {
          builder.setNewProject(true);builder.setUseDefaultProjectAsTemplate(false);builder.setPreloadServices(false);
          builder.setCreateModule(false);builder.setProjectName("owned PSI listener initialization");return kotlin.Unit.INSTANCE;
        });
        project=ProjectManagerEx.getInstanceEx().newProject(root,options);assertNotNull(project);
        assertSame(project,ProjectManagerEx.getInstanceEx().openProject(root,options.withProject(project)));
        assertTrue("Actual native synchronous commits require an opened project",project.isOpen());
        Path path=root.resolve("probe.go");Files.writeString(path,"package probe\nvar Answer = 42\n");
        VirtualFile file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);assertNotNull(file);
        document=ReadAction.compute(() -> {assertNotNull(PsiManager.getInstance(project).findFile(file));return FileDocumentManager.getInstance().getDocument(file);});
        assertNotNull(document);assertNotNull(ReadAction.compute(() -> PsiDocumentManager.getInstance(project).getPsiFile(document)));
        // Warm the actual daemon before replacing its factory dependency. Only the target constructor is interrupted.
        DaemonCodeAnalyzer.getInstance(project);
      } catch(Exception | AssertionError failure) {close();throw failure;}
    }

    Object construct(boolean interrupt) throws Exception {
      Class<?> handler=Class.forName(HANDLER);
      var constructor=handler.getDeclaredConstructor(Project.class,DaemonCodeAnalyzerEx.class,Disposable.class);constructor.setAccessible(true);
      EditorFactory actual=EditorFactory.getInstance();
      Disposable override=Disposer.newDisposable("temporary exact-thread EditorFactory delegation");
      boolean cancelled=false;Object result=null;
      try {
        ServiceContainerUtil.replaceService(ApplicationManager.getApplication(),EditorFactory.class,new InjectingFactory(actual,constructorOwner,registrations,interrupt),override);
        try {result=constructor.newInstance(project,DaemonCodeAnalyzer.getInstance(project),constructorOwner);}
        catch(InvocationTargetException failure) {
          if(interrupt && failure.getCause() instanceof ProcessCanceledException)cancelled=true;
          else throw failure;
        }
      } finally {Disposer.dispose(override);}
      assertSame("Global service is restored before any document edit or queued callback",actual,EditorFactory.getInstance());
      assertEquals("Only the selected second registration may be interrupted",interrupt,cancelled);
      return result;
    }

    void commit(String prefix) throws Exception {
      EdtTestUtil.runInEdtAndWait(() -> WriteCommandAction.runWriteCommandAction(project,() -> {
        document.insertString(0,prefix);PsiDocumentManager.getInstance(project).commitDocument(document);
      }));
    }

    @Override public void close() throws Exception {
      EdtTestUtil.runInEdtAndWait(() -> {
        if(!Disposer.isDisposed(constructorOwner))Disposer.dispose(constructorOwner);
        // The platform transaction listener is project-scoped. Dispose this owned project too;
        // no listener from the control remains attached to the shared light-test project.
        if(project!=null && !project.isDisposed())assertTrue(ProjectManagerEx.getInstanceEx().forceCloseProject(project,false));
      });
      try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
  }

  private JsonObject report() throws Exception {
    String property=System.getProperty("openidea.platformPsiListenerPatchManifest");
    assertNotNull("Prepare the private public-source platform patch before this regression",property);
    return JsonParser.parseString(Files.readString(Path.of(property))).getAsJsonObject();
  }

  private void assertClassOrigin(Class<?> type,String resource,Path expectedJar) throws Exception {
    // This pinned PathClassLoader may intentionally provide a null CodeSource URL.
    // Its own resource URL and bytes still identify the actual default-loader JAR.
    var source=type.getProtectionDomain().getCodeSource();
    if(source!=null && source.getLocation()!=null)assertEquals(expectedJar,Path.of(source.getLocation().toURI()).toRealPath());
    var url=type.getClassLoader().getResource(resource);assertNotNull(url);
    assertEquals("jar",url.getProtocol());
    String address=url.toExternalForm();String suffix="!/"+resource;
    assertTrue(address.startsWith("jar:"));assertTrue(address.endsWith(suffix));
    assertEquals(expectedJar,Path.of(java.net.URI.create(address.substring(4,address.length()-suffix.length()))).toRealPath());
  }

  private static String sha(Path file) throws Exception {try(InputStream input=Files.newInputStream(file)){return sha(input);}}
  private static String sha(InputStream input) throws Exception {
    MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] block=new byte[65536];int size;
    while((size=input.read(block))!=-1)digest.update(block,0,size);return HexFormat.of().formatHex(digest.digest());
  }

  private static final class InjectingFactory extends EditorFactory {
    private final EditorFactory delegate;
    private final EditorEventMulticaster multicaster;
    InjectingFactory(EditorFactory delegate,Disposable owner,AtomicInteger registrations, boolean interrupt) {
      this.delegate=delegate;
      Thread targetThread=Thread.currentThread();
      EditorEventMulticaster actual=delegate.getEventMulticaster();
      multicaster=(EditorEventMulticaster)Proxy.newProxyInstance(EditorEventMulticaster.class.getClassLoader(),new Class<?>[]{com.intellij.openapi.editor.ex.EditorEventMulticasterEx.class},(proxy,method,args) -> {
        if(Thread.currentThread()==targetThread && method.getName().equals("addDocumentListener") && args!=null && args.length==2 && args[1]==owner && registrations.incrementAndGet()==2 && interrupt) throw new ProcessCanceledException();
        try{return method.invoke(actual,args);}catch(InvocationTargetException error){throw error.getCause();}
      });
    }
    @Override public com.intellij.openapi.editor.Document createDocument(java.lang.CharSequence p0) { return delegate.createDocument(p0); }
    @Override public com.intellij.openapi.editor.Document createDocument(char[] p0) { return delegate.createDocument(p0); }
    @Override public com.intellij.openapi.editor.Document createDocument(boolean p0) { return delegate.createDocument(p0); }
    @Override public com.intellij.openapi.editor.Document createDocument(java.lang.CharSequence p0, boolean p1, boolean p2) { return delegate.createDocument(p0, p1, p2); }
    @Override public com.intellij.openapi.editor.Editor createEditor(com.intellij.openapi.editor.Document p0) { return delegate.createEditor(p0); }
    @Override public com.intellij.openapi.editor.Editor createViewer(com.intellij.openapi.editor.Document p0) { return delegate.createViewer(p0); }
    @Override public com.intellij.openapi.editor.Editor createEditor(com.intellij.openapi.editor.Document p0, com.intellij.openapi.project.Project p1) { return delegate.createEditor(p0, p1); }
    @Override public com.intellij.openapi.editor.Editor createEditor(com.intellij.openapi.editor.Document p0, com.intellij.openapi.project.Project p1, com.intellij.openapi.editor.EditorKind p2) { return delegate.createEditor(p0, p1, p2); }
    @Override public com.intellij.openapi.editor.Editor createEditor(com.intellij.openapi.editor.Document p0, com.intellij.openapi.project.Project p1, com.intellij.openapi.fileTypes.FileType p2, boolean p3) { return delegate.createEditor(p0, p1, p2, p3); }
    @Override public com.intellij.openapi.editor.Editor createEditor(com.intellij.openapi.editor.Document p0, com.intellij.openapi.project.Project p1, com.intellij.openapi.vfs.VirtualFile p2, boolean p3) { return delegate.createEditor(p0, p1, p2, p3); }
    @Override public com.intellij.openapi.editor.Editor createEditor(com.intellij.openapi.editor.Document p0, com.intellij.openapi.project.Project p1, com.intellij.openapi.vfs.VirtualFile p2, boolean p3, com.intellij.openapi.editor.EditorKind p4) { return delegate.createEditor(p0, p1, p2, p3, p4); }
    @Override public com.intellij.openapi.editor.Editor createViewer(com.intellij.openapi.editor.Document p0, com.intellij.openapi.project.Project p1) { return delegate.createViewer(p0, p1); }
    @Override public com.intellij.openapi.editor.Editor createViewer(com.intellij.openapi.editor.Document p0, com.intellij.openapi.project.Project p1, com.intellij.openapi.editor.EditorKind p2) { return delegate.createViewer(p0, p1, p2); }
    @Override public void releaseEditor(com.intellij.openapi.editor.Editor p0) { delegate.releaseEditor(p0); }
    @Override public java.util.stream.Stream<com.intellij.openapi.editor.Editor> editors(com.intellij.openapi.editor.Document p0, com.intellij.openapi.project.Project p1) { return delegate.editors(p0, p1); }
    @Override public com.intellij.openapi.editor.Editor[] getAllEditors() { return delegate.getAllEditors(); }
    @Override public java.util.List<com.intellij.openapi.editor.Editor> getEditorList() { return delegate.getEditorList(); }
    @Override public void addEditorFactoryListener(com.intellij.openapi.editor.event.EditorFactoryListener p0) { delegate.addEditorFactoryListener(p0); }
    @Override public void addEditorFactoryListener(com.intellij.openapi.editor.event.EditorFactoryListener p0, com.intellij.openapi.Disposable p1) { delegate.addEditorFactoryListener(p0, p1); }
    @Override public void removeEditorFactoryListener(com.intellij.openapi.editor.event.EditorFactoryListener p0) { delegate.removeEditorFactoryListener(p0); }
    @Override public com.intellij.openapi.editor.event.EditorEventMulticaster getEventMulticaster() { return multicaster; }
    @Override public void refreshAllEditors() { delegate.refreshAllEditors(); }
  }
}
