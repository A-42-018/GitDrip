package com.gitdrip.app

import com.gitdrip.app.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BridgeTest {
    @Test fun planDropsEmptyBatchesAndSortsBySeq() {
        val j = JSONObject(buildPlan("proj", "https://github.com/o/r", "main", listOf(
            PlanBatch(2, "feat: b", listOf("b/z.js")), PlanBatch(1, "feat: a", listOf("a/x.js", "a/y.js")), PlanBatch(3, "empty", emptyList()))))
        val b = j.getJSONArray("batches")
        assertEquals(2, b.length()); assertEquals(1, b.getJSONObject(0).getInt("id")); assertEquals(2, b.getJSONObject(0).getJSONArray("files").length())
        assertEquals("https://github.com/o/r", j.getString("repo"))
    }
    @Test fun planOmitsBlankRepo() = assertFalse(JSONObject(buildPlan("p", "", "main", listOf(PlanBatch(1, "m", listOf("f"))))).has("repo"))
    @Test fun parsesSuccessResult() {
        val r = parseResult("""{"req":"r1","state":"SUCCESS","task_id":"t1","commit":"abc1234","reason":"","exit_code":0,"files_changed":2,
            "batches":[{"id":1,"status":"SUCCESS","commit":"abc1234"},{"id":2,"status":"PENDING","commit":""}]}""")!!
        assertTrue(r.isFinal); assertEquals("abc1234", r.commit); assertEquals(2, r.batches.size); assertEquals("PENDING", r.batches[1].status)
    }
    @Test fun runningIsNotFinalButLockedIs() {
        assertFalse(parseResult("""{"state":"RUNNING"}""")!!.isFinal)
        assertTrue(parseResult("""{"state":"PENDING","exit_code":4,"reason":"project locked"}""")!!.isFinal)
        assertTrue(parseResult("""{"state":"PENDING_RETRY","exit_code":7}""")!!.isFinal)
    }
    @Test fun halfWrittenFileIsIgnored() { assertNull(parseResult("")); assertNull(parseResult("{\"state\":")) }
    @Test fun parsesDoctor() {
        val d = parseDoctor("""{"tools":{"git":true,"curl":true,"jq":false,"crond":true},"storage_link":true,"exchange_writable":true,"auth":false}""")!!
        assertEquals(false, d.first { it.first == "jq" }.second); assertEquals(true, d.first { it.first == "~/storage linked" }.second)
    }
    @Test fun requestIdsAreSafeAndUnique() {
        val a = newRequestId(); val b = newRequestId()
        assertNotEquals(a, b); assertTrue(Regex("^[A-Za-z0-9._-]{1,64}$").matches(a))
    }
    @Test fun resultCarriesOutputAttemptAndBatchId() {
        val r = parseResult("""{"state":"PENDING_RETRY","task_id":"t","batch_id":"2","attempt":3,"next_retry_at":"2026-10-07T10:00:00Z","output":"line1\nline2","exit_code":7}""")!!
        assertEquals(2, r.batchId); assertEquals(3, r.attempt); assertEquals("2026-10-07T10:00:00Z", r.nextRetryAt); assertTrue(r.output.contains("line2"))
        assertNull(parseResult("""{"state":"RUNNING"}""")!!.batchId)
    }
    @Test fun parsesHistory() {
        val h = parseHistory("""{"state":"OK","batches":[{"id":1,"status":"SUCCESS","commit":"abc1234"}],"tasks":[
            {"id":"t2","batch_id":"2","state":"FAILED","commit":"","files_changed":0,"attempt":1,"reason":"push failed","output":"x","error_class":"auth","next_retry_at":"","created_at":"2026-10-07T09:00:00Z","started_at":"2026-10-07T09:00:01Z","finished_at":"2026-10-07T09:00:05Z"},
            {"id":"t1","batch_id":1,"state":"SUCCESS","commit":"abc1234","files_changed":2,"attempt":1,"reason":"","output":"","error_class":"","next_retry_at":"","created_at":"2026-10-06T09:00:00Z","started_at":"","finished_at":""}]}""")!!
        assertEquals(2, h.tasks.size); assertEquals(2, h.tasks[0].batchId); assertEquals("auth", h.tasks[0].errorClass)
        assertEquals("abc1234", h.tasks[1].commit); assertEquals(1, h.batches.size)
    }
    @Test fun historyRejectsErrorsAndGarbage() {
        assertNull(parseHistory("""{"state":"ERROR","reason":"jq missing"}""")); assertNull(parseHistory("")); assertNull(parseHistory("""{"state":"OK"}"""))
    }
    @Test fun runArgsPlainRunHasNoPrFlags() {
        assertEquals(listOf("bridge", "r1", "run", "proj", "--sync"), runArgs("r1", "proj", null, null))
        assertEquals(listOf("bridge", "r1", "run", "proj", "3", "--sync"), runArgs("r1", "proj", 3, null))
    }
    @Test fun runArgsPrFlowModes() {
        assertEquals(listOf("bridge", "r2", "pr-flow", "proj", "2", "--issue", "--sync"), runArgs("r2", "proj", 2, PrMode.Open))
        assertEquals(listOf("bridge", "r3", "pr-flow", "proj", "--issue", "--merge", "--sync"), runArgs("r3", "proj", null, PrMode.OpenMerge))
    }
    @Test fun watchArgsModes() {
        assertEquals(listOf("bridge", "w1", "watch-scan", "proj"), watchArgs("w1", "proj", false, null))
        assertEquals(listOf("bridge", "w2", "watch-scan", "proj", "--commit"), watchArgs("w2", "proj", true, null))
        assertEquals(listOf("bridge", "w3", "watch-scan", "proj", "--tick", "scan"), watchArgs("w3", "proj", false, WatchTick.Scan))
        assertEquals(listOf("bridge", "w4", "watch-scan", "proj", "--tick", "off"), watchArgs("w4", "proj", false, WatchTick.Off))
        assertEquals(listOf("off", "scan", "commit"), WatchTick.values().map { it.arg })
    }
    @Test fun resultCarriesPrFields() {
        val r = parseResult("""{"state":"SUCCESS","batch_id":2,"pr":7,"pr_url":"https://github.com/o/r/pull/7","commit":"abc1234","exit_code":0}""")!!
        assertEquals(7, r.pr); assertEquals("https://github.com/o/r/pull/7", r.prUrl); assertTrue(r.isFinal)
        val n = parseResult("""{"state":"SUCCESS","pr":null,"pr_url":""}""")!!
        assertNull(n.pr); assertEquals("", n.prUrl)
    }

    @Test fun parsesWatchBatchFields() {
        val r = parseResult("""{"req":"r","state":"OK","batches":[{"id":1,"status":"SUCCESS","commit":"abc"},{"id":2,"status":"PENDING","commit":"","origin":"watch","message":"feat: add api module","files":["api/a.js","api/b.js"]}]}""")!!
        assertEquals("", r.batches[0].origin)
        assertTrue(r.batches[0].files.isEmpty())
        assertEquals("watch", r.batches[1].origin)
        assertEquals("feat: add api module", r.batches[1].message)
        assertEquals(listOf("api/a.js", "api/b.js"), r.batches[1].files)
    }
}
