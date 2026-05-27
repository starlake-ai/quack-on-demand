package ai.starlake.acl.parser

import ai.starlake.acl.model.{Config, DenyReason, TableRef}
import net.sf.jsqlparser.schema.Table

/** Qualification pipeline: takes raw JSqlParser Table objects (output of TableExtractor),
  * applies DialectMapper to resolve partial names using Config defaults, and partitions
  * into successfully qualified TableRefs and DenyReason errors.
  */
object TableQualifier:

  /** Qualify a list of raw JSqlParser Table objects into TableRefs.
    *
    * @param tables raw Table objects from TableExtractor
    * @param config configuration with dialect and default database/schema
    * @return tuple of (successfully qualified TableRefs as Set, list of DenyReasons for failures)
    */
  def qualify(tables: List[Table], config: Config): (Set[TableRef], List[DenyReason]) =
    val mapper = DialectMapper.forConfig(config)
    val (errors, refs) = tables.map(t => mapper.toTableRef(t, config)).partitionMap(identity)
    (refs.toSet, errors)
