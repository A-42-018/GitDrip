package org.junit
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME) annotation class Test
fun assertEquals(a: Any?, b: Any?) { if (a != b) throw AssertionError("expected <$a> but was <$b>") }
fun assertEquals(m: String, a: Any?, b: Any?) { if (a != b) throw AssertionError("$m: expected <$a> but was <$b>") }
fun assertEquals(a: Double, b: Double, d: Double) { if (Math.abs(a-b) > d) throw AssertionError("expected <$a> but was <$b>") }
fun assertEquals(a: Float, b: Float, d: Float) { if (Math.abs(a-b) > d) throw AssertionError("expected <$a> but was <$b>") }
fun assertTrue(c: Boolean) { if (!c) throw AssertionError("expected true") }
fun assertTrue(m: String, c: Boolean) { if (!c) throw AssertionError(m) }
fun assertFalse(c: Boolean) { if (c) throw AssertionError("expected false") }
fun assertFalse(m: String, c: Boolean) { if (c) throw AssertionError(m) }
fun assertNull(a: Any?) { if (a != null) throw AssertionError("expected null but was <$a>") }
fun assertNull(m: String, a: Any?) { if (a != null) throw AssertionError("$m: expected null but was <$a>") }
fun assertNotNull(a: Any?) { if (a == null) throw AssertionError("expected non-null") }
fun assertArrayEquals(a: ByteArray, b: ByteArray) { if (!a.contentEquals(b)) throw AssertionError("arrays differ") }
fun assertNotEquals(a: Any?, b: Any?) { if (a == b) throw AssertionError("expected different, both <$a>") }
fun <T : Throwable> assertThrows(c: Class<T>, f: () -> Unit) {
  try { f() } catch (e: Throwable) { if (c.isInstance(e)) return; throw AssertionError("wrong exception: $e") }
  throw AssertionError("expected ${c.simpleName}")
}
