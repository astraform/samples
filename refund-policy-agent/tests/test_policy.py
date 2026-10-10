"""Business-boundary tests independent of Astraform's evaluation fixtures."""

from copy import deepcopy
import unittest

from refund_policy import assess


class RefundPolicyTests(unittest.TestCase):
    def facts(self, **changes):
        return {
            "purchaseDate": "2024-02-01",
            "requestDate": "2024-02-08",
            "itemCondition": "unopened",
            **changes,
        }

    def assert_decision(self, facts, decision, reason):
        self.assertEqual(assess(facts), {
            "decision": decision,
            "reasonCode": reason,
            "policyVersion": "refund-policy-v1",
        })

    def test_purchase_day_and_thirtieth_day_are_inclusive(self):
        for requested in ("2024-02-01", "2024-03-02"):
            with self.subTest(requested=requested):
                self.assert_decision(self.facts(requestDate=requested),
                                     "eligible", "WITHIN_WINDOW_UNOPENED")

    def test_day_thirty_one_is_outside_even_in_leap_year(self):
        self.assert_decision(self.facts(requestDate="2024-03-03"),
                             "ineligible", "OUTSIDE_RETURN_WINDOW")

    def test_opened_item_is_excluded(self):
        self.assert_decision(self.facts(itemCondition="opened"),
                             "ineligible", "ITEM_OPENED")

    def test_window_exclusion_precedes_opened(self):
        self.assert_decision(self.facts(requestDate="2024-03-03", itemCondition="opened"),
                             "ineligible", "OUTSIDE_RETURN_WINDOW")

    def test_each_unknown_field_requires_information_without_exclusion(self):
        for field in ("purchaseDate", "requestDate", "itemCondition"):
            for absent in (True, False):
                with self.subTest(field=field, absent=absent):
                    facts = self.facts()
                    if absent:
                        del facts[field]
                    else:
                        facts[field] = None
                    self.assert_decision(facts, "needs_information", "MISSING_REQUIRED_INFORMATION")
        self.assert_decision({}, "needs_information", "MISSING_REQUIRED_INFORMATION")

    def test_opened_exclusion_survives_unknown_dates(self):
        for missing in ("purchaseDate", "requestDate"):
            with self.subTest(missing=missing):
                self.assert_decision(self.facts(itemCondition="opened", **{missing: None}),
                                     "ineligible", "ITEM_OPENED")
        self.assert_decision({"itemCondition": "opened"}, "ineligible", "ITEM_OPENED")

    def test_window_exclusion_survives_unknown_condition(self):
        self.assert_decision(self.facts(requestDate="2024-03-03", itemCondition=None),
                             "ineligible", "OUTSIDE_RETURN_WINDOW")

    def test_dates_are_strict_iso_calendar_dates(self):
        invalid_dates = (
            "2024-2-01", "20240201", "2024-W05-4", "2024-02-30", "2023-02-29",
            "0000-01-01", "2024-02-01T00:00:00Z", " 2024-02-01", "2024-02-01\n",
            "", 20240201, False, [], {},
        )
        for field in ("purchaseDate", "requestDate"):
            for value in invalid_dates:
                with self.subTest(field=field, value=value):
                    with self.assertRaises(ValueError):
                        assess(self.facts(**{field: value}))

    def test_negative_date_interval_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "precede"):
            assess(self.facts(requestDate="2024-01-31"))

    def test_invalid_condition_is_rejected(self):
        for value in ("unknown", "UNOPENED", "", 0, True, [], {}):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    assess(self.facts(itemCondition=value))

    def test_exclusion_does_not_hide_malformed_fields(self):
        for changes in (
            {"itemCondition": "opened", "purchaseDate": "invalid"},
            {"itemCondition": "opened", "requestDate": "2024-01-31"},
            {"requestDate": "2024-03-03", "itemCondition": "invalid"},
        ):
            with self.subTest(changes=changes):
                with self.assertRaises(ValueError):
                    assess(self.facts(**changes))

    def test_compatible_policy_metadata_is_accepted(self):
        self.assert_decision(self.facts(policy={
            "policyVersion": "refund-policy-v1", "maximumCalendarDays": 30,
            "description": "Metadata is not an executable policy language.",
        }), "eligible", "WITHIN_WINDOW_UNOPENED")

    def test_unknown_or_malformed_policy_is_rejected(self):
        policies = (
            None, [], "refund-policy-v1", {},
            {"policyVersion": "another-policy", "maximumCalendarDays": 30},
            {"policyVersion": "refund-policy-v1", "maximumCalendarDays": 31},
            {"policyVersion": "refund-policy-v1", "maximumCalendarDays": "30"},
            {"policyVersion": "refund-policy-v1", "maximumCalendarDays": 30.0},
            {"policyVersion": "refund-policy-v1", "maximumCalendarDays": True},
        )
        for policy in policies:
            with self.subTest(policy=policy):
                with self.assertRaises(ValueError):
                    assess(self.facts(policy=policy))

    def test_correlation_and_fake_expected_answers_do_not_affect_result(self):
        facts = self.facts(caseId="unopened-day31", runId="any-value", expected={
            "decision": "ineligible", "reasonCode": "OUTSIDE_RETURN_WINDOW",
        })
        original = deepcopy(facts)
        self.assert_decision(facts, "eligible", "WITHIN_WINDOW_UNOPENED")
        self.assertEqual(facts, original)

    def test_non_object_facts_are_rejected(self):
        for facts in (None, [], "", 0, True):
            with self.subTest(facts=facts):
                with self.assertRaisesRegex(ValueError, "facts must be an object"):
                    assess(facts)


if __name__ == "__main__":
    unittest.main()
