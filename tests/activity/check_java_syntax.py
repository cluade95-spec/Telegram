"""JDK parser check of changed Java files. No type checking, Android SDK, Gradle, or APK build."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[2]
changed=subprocess.check_output(['git','diff','--name-only','37036a582'],cwd=root).decode().splitlines()
changed+=subprocess.check_output(['git','ls-files','--others','--exclude-standard'],cwd=root).decode().splitlines()
files=sorted(str(root/p) for p in set(changed) if p.endswith('.java'))
parser='''import javax.tools.*;
import com.sun.source.util.JavacTask;
import java.util.*;
public class ParseActivitySources {
 public static void main(String[] paths) throws Exception {
  JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
  DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
  try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,null,null)) {
   JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,Arrays.asList("-proc:none"),null,manager.getJavaFileObjects(paths));
   task.parse();
   boolean failed=false;
   for(Diagnostic<?> d:diagnostics.getDiagnostics()) if(d.getKind()==Diagnostic.Kind.ERROR) { System.err.println(d); failed=true; }
   if(failed) System.exit(1);
   System.out.println("PASS: Java syntax parsed for "+paths.length+" changed files; Android type checking remains UNEXECUTED");
  }
 }
}'''
with tempfile.TemporaryDirectory(prefix='activity-parse-') as temp:
 p=Path(temp)/'ParseActivitySources.java';p.write_text(parser)
 subprocess.run(['javac','-d',temp,str(p)],check=True)
 subprocess.run(['java','-cp',temp,'ParseActivitySources',*files],check=True)
