Produce JSON using this shape:
{
  "userGoal": "string",
  "deliverableTargets": ["DOC|PPT|SUMMARY"],
  "timeRange": "string",
  "audience": "string",
  "constraints": ["string"],
  "missingSlots": ["string"],
  "scenarioPath": ["A_IM", "B_PLANNING"]
}

Rules:
1. Return JSON only.
2. Infer the smallest useful set of deliverable targets.
3. Put unanswered critical details in missingSlots.
4. Always include A_IM and B_PLANNING in scenarioPath.
