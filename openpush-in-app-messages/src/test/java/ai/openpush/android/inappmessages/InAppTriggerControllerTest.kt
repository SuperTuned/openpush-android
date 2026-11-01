package ai.openpush.android.inappmessages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppTriggerControllerTest {
    @Test
    fun emptyGroupsMatch() {
        assertTrue(InAppTriggerController.matches(emptyList(), emptyMap(), 0.0, 0.0))
    }

    @Test
    fun conditionsInAGroupUseAnd() {
        val group = listOf(
            condition("custom", "level", "greater_or_equal", 10),
            condition("custom", "plan", "equal", "pro"),
        )

        assertTrue(
            InAppTriggerController.matches(
                listOf(group),
                mapOf("level" to 12, "plan" to "pro"),
                0.0,
                0.0,
            ),
        )
        assertFalse(
            InAppTriggerController.matches(
                listOf(group),
                mapOf("level" to 12, "plan" to "free"),
                0.0,
                0.0,
            ),
        )
    }

    @Test
    fun groupsUseOr() {
        val groups = listOf(
            listOf(condition("custom", "plan", "equal", "pro")),
            listOf(condition("custom", "region", "equal", "in")),
        )

        assertTrue(
            InAppTriggerController.matches(
                groups,
                mapOf("plan" to "free", "region" to "in"),
                0.0,
                0.0,
            ),
        )
    }

    @Test
    fun lifecycleAndExistenceConditionsUseRuntimeValues() {
        val group = listOf(
            condition("session_time", null, "greater", 30),
            condition("min_time_since", null, "greater_or_equal", 60),
            condition("custom", "onboarded", "exists", null),
            condition("custom", "blocked", "not_exists", null),
        )

        assertTrue(
            InAppTriggerController.matches(
                listOf(group),
                mapOf("onboarded" to true),
                31.0,
                60.0,
            ),
        )
    }

    /**
     * `in` asks whether the trigger value is one of the device value's members, not whether it
     * appears somewhere in its text. A comma-separated string is the wire's way of writing a list,
     * so it is split and each part trimmed before the comparison; only a device value that is not a
     * list at all falls back to plain equality.
     */
    @Test
    fun inSplitsCommaSeparatedValuesAndTrims() {
        // A substring of a member is not a member.
        assertFalse(matchesIn("tier", "sil", mapOf("tier" to "gold, silver")))
        assertTrue(matchesIn("tier", "silver", mapOf("tier" to "gold, silver")))
        // The leading space of " silver" is the separator's, not the value's.
        assertTrue(matchesIn("tier", "gold", mapOf("tier" to "gold, silver")))

        // Members that parse as numbers compare as numbers, so "02" is 2.
        assertTrue(matchesIn("level", 2, mapOf("level" to "1, 02 , 3")))
        assertFalse(matchesIn("level", 4, mapOf("level" to "1, 2, 3")))

        // A collection needs no splitting.
        assertTrue(matchesIn("tier", "silver", mapOf("tier" to listOf("gold", "silver"))))
        assertFalse(matchesIn("tier", "bronze", mapOf("tier" to listOf("gold", "silver"))))

        // A scalar that is not a list is only "in" itself.
        assertTrue(matchesIn("level", 7, mapOf("level" to 7)))
        assertFalse(matchesIn("level", 1, mapOf("level" to 7)))
    }

    private fun matchesIn(property: String, expected: Any, values: Map<String, Any>): Boolean =
        InAppTriggerController.matches(
            listOf(listOf(condition("custom", property, "in", expected))),
            values,
            0.0,
            0.0,
        )

    private fun condition(kind: String, property: String?, op: String, value: Any?) =
        IamCondition("condition", kind, property, op, value)
}
