package com.example.crewsync.data.model

import kotlinx.serialization.Serializable

@Serializable
data class TaskTemplate(
    val id: String = "",
    val title: String = "",
    val trade: String = "",
    val description: String = "",
    val defaultChecklist: List<String> = emptyList(),
    val colorHex: String = "#38BDF8"
)

val DEFAULT_TASK_TEMPLATES = listOf(
    TaskTemplate("tpl_framing", "Framing", "Framing", "Wall, floor, and roof framing layout and assembly.", listOf("Layout plates", "Erect exterior walls", "Set roof trusses"), "#38BDF8"),
    TaskTemplate("tpl_demo", "Demo", "Demolition", "Selective demolition and site cleanout.", listOf("Safety barrier setup", "Remove non-structural walls", "Debris haul-off"), "#EF4444"),
    TaskTemplate("tpl_drywall", "Drywall", "Drywall", "Hanging, taping, mudding, and sanding drywall.", listOf("Hang drywall sheets", "Tape and first coat", "Second coat and sand"), "#F59E0B"),
    TaskTemplate("tpl_insulation", "Insulation", "Insulation", "Thermal and acoustic insulation installation.", listOf("Wall cavity batt insulation", "Ceiling blown insulation", "Vapor barrier seal"), "#EC4899"),
    TaskTemplate("tpl_trim", "Trim Work", "Carpentry", "Baseboards, casing, crown molding, and interior doors.", listOf("Set interior doors", "Install baseboards & casing", "Caulk & nail hole fill"), "#8B5CF6"),
    TaskTemplate("tpl_cabinets", "Cabinets", "Millwork", "Kitchen and bathroom cabinet layout and mounting.", listOf("Set upper cabinets", "Set base cabinets", "Hardware & adjustments"), "#10B981"),
    TaskTemplate("tpl_flooring", "Flooring and Tile", "Flooring", "Subfloor prep, tile, hardwood, or LVP installation.", listOf("Prep subfloor", "Tile layout & thinset", "Grout & clean"), "#14B8A6"),
    TaskTemplate("tpl_electrical", "Electrical", "Electrical", "Rough-in wiring, panel setup, fixtures, and trim-out.", listOf("Rough-in boxes & conduit", "Pull wire runs", "Device trim & testing"), "#FACC15"),
    TaskTemplate("tpl_mechanical", "Mechanical", "HVAC", "Ductwork, air handler, line sets, and grille installation.", listOf("Hang ductwork", "Set outdoor unit", "Trim grilles & balance"), "#06B6D4"),
    TaskTemplate("tpl_plumbing", "Plumbing", "Plumbing", "Underground piping, rough-in supply/drain lines, and fixtures.", listOf("Underground rough-in", "Top-out drain & vent lines", "Set fixtures & test"), "#3B82F6"),
    TaskTemplate("tpl_sitework", "Site Work", "Site Work", "Grading, excavation, and site preparation.", listOf("Mark utility lines", "Excavation to grade", "Compaction test"), "#84CC16"),
    TaskTemplate("tpl_landscaping", "Landscaping", "Landscaping", "Irrigation, sod, plants, and hardscaping.", listOf("Irrigation line layout", "Planting beds & sod", "Final cleanup"), "#22C55E"),
    TaskTemplate("tpl_roofing", "Roofing", "Roofing", "Underlayment, flashing, shingles/metal roofing.", listOf("Ice & water shield", "Install drip edge & shingles", "Ridge vent seal"), "#64748B"),
    TaskTemplate("tpl_painting", "Painting", "Painting", "Masking, priming, interior/exterior paint application.", listOf("Prep & mask surfaces", "Prime coat", "Two finish coats"), "#A855F7"),
    TaskTemplate("tpl_inspections", "Inspections", "Quality Control", "City framing, MEP rough-in, and final inspections.", listOf("Call in inspection", "Walk with inspector", "Sign off approval"), "#6366F1"),
    TaskTemplate("tpl_siding", "Siding", "Exteriors", "Housewrap, trim, fiber cement or vinyl siding.", listOf("Weather barrier wrap", "Corner posts & starter strip", "Install siding panels"), "#0EA5E9"),
    TaskTemplate("tpl_steel", "Steel Work", "Structural Steel", "Structural posts, beams, and welding work.", listOf("Set steel columns", "Hoist main beam", "Weld connections"), "#475569"),
    TaskTemplate("tpl_utilities", "Utilities", "Utilities", "Water, sewer, gas, and electrical utility connections.", listOf("Trench to main line", "Lay utility pipe", "Pressure test & backfill"), "#D97706")
)
