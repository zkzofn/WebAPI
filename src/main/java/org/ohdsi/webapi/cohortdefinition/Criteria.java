/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.ohdsi.webapi.cohortdefinition;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;

/**
 *
 * @author cknoll1
 */

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = As.WRAPPER_OBJECT)
@JsonSubTypes({
  @JsonSubTypes.Type(value = ConditionOccurrence.class, name = "ConditionOccurrence"),
  @JsonSubTypes.Type(value = DrugExposure.class, name = "DrugExposure"),
  @JsonSubTypes.Type(value = ProcedureOccurrence.class, name = "ProcedureOccurrence"),
  @JsonSubTypes.Type(value = Measurement.class, name = "Measurement"),
  @JsonSubTypes.Type(value = ObservationPeriod.class, name = "ObservationPeriod"),
  @JsonSubTypes.Type(value = Observation.class, name = "Observation")
})
public abstract class Criteria implements ICohortExpressionElement {
}
