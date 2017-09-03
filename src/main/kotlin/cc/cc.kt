package cc

import jdk.internal.org.objectweb.asm.ClassReader
import jdk.internal.org.objectweb.asm.util.Textifier
import jdk.internal.org.objectweb.asm.util.TraceClassVisitor
import net.lingala.zip4j.core.ZipFile
import org.objectweb.asm.Opcodes.ASM5
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.PrintWriter
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files.readAllBytes
import java.nio.file.Files.walkFileTree
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.*

fun main(args: Array<String>) {
  val compareClasses = true
  val compareOther = System.getProperty("compare.other").toBoolean()
  val compareMethodBodies = System.getProperty("compare.methods").toBoolean() 

  val classMatcher = FileSystems.getDefault().getPathMatcher("glob:*.{class}")

  extract("orig.zip", "orig")
  extract("inc.zip", "inc")

  val diff = File("diff")
  diff.deleteRecursively()
  diff.mkdir()
  val diffClasses = File(diff, "classes")
  diffClasses.mkdir()

  val orig = File("orig").toPath()
  val inc = File("inc").toPath()
  val diffClassNames = ArrayList<String>()
  var allFiles = 0
  var classFiles = 0
  walkFileTree(orig, object : SimpleFileVisitor<Path>() {
    override fun preVisitDirectory(dir: Path?, attrs: BasicFileAttributes?): FileVisitResult {
      if (orig.relativize(dir).nameCount == 2) {
        println(dir)
      }
      return super.preVisitDirectory(dir, attrs)
    }

    override fun visitFile(path: Path?, attrs: BasicFileAttributes?): FileVisitResult {
      val result = super.visitFile(path, attrs)
      val relativize = orig.relativize(path)

      val inInc = inc.resolve(relativize)
      allFiles++

      val fileName: Path? = path?.fileName
      if (compareClasses && path != null && classMatcher.matches(fileName)) {
        classFiles++

        if (!inInc.toFile().exists()) tc("M " + inInc.toString())
        else {
          val o = decompile(path, compareMethodBodies)
          val s = decompile(inInc, compareMethodBodies)
          if (sortAndTrim(o) != sortAndTrim(s)) {
            val fn = fileName.toString()
            diffClassNames.add(fn)
            File(diff, fn + ".o.txt").writeText(o)
            File(diff, fn + ".i.txt").writeText(s)
            path.toFile().copyTo(File(diffClasses, fn + ".o.class"))
            inInc.toFile().copyTo(File(diffClasses, fn + ".i.class"))
            tc("D " + inInc.toString())
          }
        }
      }
      else if (compareOther) {
        if (!inInc.toFile().exists()) tc("M " + inInc.toString())
        else if (!Arrays.equals(readAllBytes(path), readAllBytes(inInc))) tc("D " + inInc.toString())
      }

      return result
    }
  })

  tc("$allFiles files")
  tc("$classFiles total class files")
  val error = diffClassNames.isNotEmpty()
  val severity = if (error) "ERROR" else "WARNING" 
  val message = "${diffClassNames.size} different classes: " + diffClassNames.take(10).joinToString()
  tc(message, severity)
  if (error) {
    status(message, severity)
  }
  else {
    status("$classFiles total class files", "SUCCESS")
  }
}

private fun sortAndTrim(o: String) = o
    .split("\n")
    .filter { !it.contains("private transient synthetic Lgroovy/lang/MetaClass; metaClass") }
    .filter { !it.contains("// access flags") }
    .filter { !it.contains("synthetic ") }
    .filter { !it.contains("@Lkotlin/Metadata;") }
    .filter { !it.contains("    LOCALVARIABLE ") }
    .map({ it.replace("  implements groovy/lang/GroovyObject", "") })
    .map({ if (it.contains("implements") && it.contains(" groovy/lang/GroovyObject")) it.replace(" groovy/lang/GroovyObject", "") else it })
    .filter { !it.isEmpty() }
    .sorted()
    .joinToString("\n")

private fun tc(message: Any?, status: String = "WARNING") {
  println("##teamcity[message text='$message' status='$status']")
}

private fun status(message: Any?, status: String = "WARNING") {
  println("##teamcity[buildStatus text='$message' status='$status']")
}

private fun decompile(path: Path?, compareMethodBodies: Boolean): String {
  FileInputStream(path?.toFile()).use { fileInputStream ->
    try {
      val classReader = ClassReader(fileInputStream)
      val byteArrayOutputStream = ByteArrayOutputStream()
      val traceClassVisitor = TraceClassVisitor(null, object : Textifier(ASM5) {
        override fun createTextifier(): Textifier {
         return object : Textifier(ASM5) {
           override fun getText(): MutableList<Any> {
             return if (compareMethodBodies) super.getText() else Arrays.asList()
           }
         } 
        }

        override fun visitSource(file: String?, debug: String?) { // don't print debug info from Kotlin
        }
      }, PrintWriter(byteArrayOutputStream))
      classReader.accept(traceClassVisitor, ClassReader.SKIP_DEBUG and ClassReader.SKIP_CODE and ClassReader.SKIP_FRAMES)
      return byteArrayOutputStream.toString()
    }
    catch(e: Exception) {
      println(e.message)
      e.printStackTrace()
      return "<null>"
    }
  }
}

private fun extract(file: String, dest: String) {
  println("deleting $dest")
  File(dest).delete()
  println("extracting $file")
  ZipFile(file).extractAll(dest)
}
