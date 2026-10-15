import kotlin.reflect.full.*
fun main(args: Array<String>) {
  var p = 0; var f = 0
  for (n in args) {
    val k = Class.forName(n).kotlin
    for (m in k.java.declaredMethods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }) {
      val o = k.java.getDeclaredConstructor().newInstance()
      try { m.invoke(o); p++ } catch (e: java.lang.reflect.InvocationTargetException) { f++; println("FAIL ${k.simpleName}.${m.name}: ${e.targetException}") }
    }
  }
  println("kotlin tests: $p passed, $f failed")
}
