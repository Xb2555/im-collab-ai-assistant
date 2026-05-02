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
5. Do not map unsupported requested outputs to DOC/PPT/SUMMARY just because they are similar.
6. If the user asks for whiteboard/canvas/direct IM/archive/search/spreadsheet/approval or another unsupported output,
   put that unsupported output label in deliverableTargets and add one natural missingSlot about converting it to DOC Mermaid, PPT, or SUMMARY.
7. If the user asks for a diagram inside a document, use DOC and put the diagram/Mermaid requirement in constraints.
