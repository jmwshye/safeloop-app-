package com.safeloop.app

import org.junit.Assert.*
import org.junit.Test

class WorkerFlowTest {
 @Test fun attendanceDoesNotRequireSafetyChecklist(){val before=WorkerState();val after=before.attend();assertEquals(0,after.checkCount);assertNotNull(after.currentShift);assertEquals(180000,after.currentShift!!.wage);assertThrows(IllegalArgumentException::class.java){after.attend()}}
 @Test fun checkoutCompletesWorkEvenWhenChecksAreMissing(){val started=WorkerState(monitoring=true).attend();val finished=started.checkout();assertNull(finished.currentShift);assertNotNull(finished.todayShift!!.end);assertFalse(finished.monitoring);assertEquals(0,finished.checkCount)}
 @Test fun csvEscapesSpreadsheetExpressionsAndLabelsPreview(){val state=WorkerState(name="=HYPERLINK(test)").attend();val csv=workCsv(state);assertTrue(csv.startsWith("\uFEFF"));assertTrue(csv.contains("'=HYPERLINK(test)"));assertTrue(csv.contains("체험 기록"));assertFalse(csv.contains("서버 기록"))}
}
