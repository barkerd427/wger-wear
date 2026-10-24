package de.wger.spike.sync

import com.powersync.db.schema.Column
import com.powersync.db.schema.Schema
import com.powersync.db.schema.Table

/**
 * Client-side subset of the wger 2.7 PowerSync schema, column names copied from
 * wger-project/flutter lib/database/powersync/tables/routines.dart. Only the three manager tables
 * that wger's publication (core migration 0027) actually syncs are available; day/slot/config
 * structure is NOT synced and has to come over REST.
 */
val wgerGymSchema = Schema(
    Table(
        "manager_routine",
        listOf(
            Column.text("name"),
            Column.text("description"),
            Column.text("created"),
            Column.text("start"),
            Column.text("end"),
            Column.integer("is_template"),
            Column.integer("is_public"),
            Column.integer("fit_in_week"),
        ),
    ),
    Table(
        "manager_workoutsession",
        listOf(
            Column.integer("routine_id"),
            Column.integer("day_id"),
            Column.text("notes"),
            Column.text("impression"),
            Column.text("datetime_start"),
            Column.text("datetime_end"),
            Column.text("date"),
            Column.text("time_start"),
            Column.text("time_end"),
        ),
    ),
    Table(
        "manager_workoutlog",
        listOf(
            Column.integer("exercise_id"),
            Column.integer("routine_id"),
            Column.text("session_id"),
            Column.integer("iteration"),
            Column.integer("slot_entry_id"),
            Column.real("rir"),
            Column.real("rir_target"),
            Column.real("repetitions"),
            Column.real("repetitions_target"),
            Column.integer("repetitions_unit_id"),
            Column.real("weight"),
            Column.real("weight_target"),
            Column.integer("weight_unit_id"),
            Column.text("date"),
        ),
    ),
)
