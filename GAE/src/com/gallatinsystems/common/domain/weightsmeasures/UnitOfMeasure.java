package com.gallatinsystems.common.domain.weightsmeasures;

import javax.jdo.annotations.IdentityType;
import javax.jdo.annotations.PersistenceCapable;

import com.gallatinsystems.framework.domain.BaseDomain;
@PersistenceCapable(identityType = IdentityType.APPLICATION)
public class UnitOfMeasure extends BaseDomain{

	/**
	 * 
	 */
	private static final long serialVersionUID = -713244155250701779L;
	private String name;
	private String code;
	private String description;
	private UnitOfMeasureType type = null;
	private String notation = null;
	private UnitOfMeasureSystem system = null;
	
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getCode() {
		return code;
	}
	public void setCode(String code) {
		this.code = code;
	}
	public String getDescription() {
		return description;
	}
	public void setDescription(String description) {
		this.description = description;
	}
	public UnitOfMeasureType getType() {
		return type;
	}
	public void setType(UnitOfMeasureType type) {
		this.type = type;
	}
	public String getNotation() {
		return notation;
	}
	public void setNotation(String notation) {
		this.notation = notation;
	}
	public UnitOfMeasureSystem getSystem() {
		return system;
	}
	public void setSystem(UnitOfMeasureSystem system) {
		this.system = system;
	}
	public enum UnitOfMeasureSystem{IMPERIAL,METRIC,OTHER}
	public enum UnitOfMeasureType{LENGTH, VOLUME, MASS, TIME, CURRENT, TEMPERATURE, AMOUNT_OF_SUBSTANCE}
}
