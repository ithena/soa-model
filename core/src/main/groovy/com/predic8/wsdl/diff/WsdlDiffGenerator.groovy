/* Copyright 2012 predic8 GmbH, www.predic8.com
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 http://www.apache.org/licenses/LICENSE-2.0
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License. */

package com.predic8.wsdl.diff

import groovy.xml.QName

import org.apache.commons.logging.*

import com.predic8.schema.ComplexType;
import com.predic8.schema.Element;
import com.predic8.schema.diff.*
import com.predic8.soamodel.*
import com.predic8.wsdl.*

class WsdlDiffGenerator extends AbstractDiffGenerator{

	private Log log = LogFactory.getLog(this.class)

	public WsdlDiffGenerator(){	}

	public WsdlDiffGenerator(Definitions a, Definitions b){
		this.a = a
		this.b = b
	}

	List<Difference> compare() {
		def diffs = []
		def lDiffs = []
		
		diffs.addAll(compareDocumentation(a, b))
		
		if( a.targetNamespace != b.targetNamespace )
			diffs << new Difference(description:"TargetNamespace changed from ${a.targetNamespace} to ${b.targetNamespace}.", breaks:true)

		if( a.services[0] && b.services[0] && a.services[0].name != b.services[0].name )
			diffs << new Difference(description:"Servicename changed from ${a.services[0].name} to ${b.services[0].name}.", breaks:false)

		diffs.addAll(comparePortTypes())
		
		diffs.addAll(0, compareTypes())

		lDiffs.addAll(compareDocumentation(a.services[0], b.services[0]))
		if ( a.services[0] && b.services[0] ) {
			lDiffs.addAll(comparePorts())
		}
		if(lDiffs) diffs << new Difference(description:"Service ${a.services[0].name}:", type : 'service', diffs: lDiffs)
		
		if(diffs) return [new Difference(description:"Definitions:", type : 'definitions', diffs: diffs)]
		[]
	}

	private List<Difference> compareTypes(){
		def diffs = compareDocumentation(a.localTypes, b.localTypes)
		def lDiffs = compareSchemas()
		if(lDiffs) diffs << new Difference(description:"Types: ", breaks:false,  diffs: lDiffs, type: 'types')
		diffs
	}

	private List<Difference> comparePorts(){
		def aPorts = a.services[0].ports
		def bPorts = b.services[0].ports
		def diffs = []
		diffs.addAll(compare(aPorts, bPorts,
				{ new Difference(description:"Port ${it.name} removed.", breaks:true, safe:false) },
				{ new Difference(description:"Port ${it.name} added.", safe:true, breaks:false) }))
		def ports = aPorts.name.intersect(bPorts.name)
		ports.each{ portName ->
			Port aPort = aPorts.find{ it.name == portName}
			Port bPort = bPorts.find{ it.name == portName}
			def lDiffs = compareDocumentation(aPort, bPort)
			if(lDiffs) diffs << new Difference(description:"Port $portName:", diffs : lDiffs)
			if(aPort.address.location != bPort.address.location)
				diffs << new Difference(description:"The location of the port $portName changed form ${aPort.address.location} to ${bPort.address.location}.", breaks:true, safe:false)
		}
		diffs
	}

	private List<Difference> comparePortTypes(){
		def aPortTypes = a.portTypes
		def bPortTypes = b.portTypes
		def diffs = []
		diffs.addAll( compare(aPortTypes, bPortTypes,
				{ new Difference(description:"PortType ${it.name} removed." , breaks:true) },
				{ new Difference(description:"PortType ${it.name} added." , safe:true) }))

		def ptNames = aPortTypes.name.intersect(bPortTypes.name)
		ptNames.each{ ptName ->
			PortType aPT = aPortTypes.find{ it.name == ptName}
			PortType bPT = bPortTypes.find{ it.name == ptName}
			diffs.addAll(comparePortType(aPT, bPT))
		}
		diffs
	}

	private List<Difference> comparePortType(aPT, bPT){
		def diffs = compareDocumentation(aPT, bPT)
		diffs.addAll(compareOperations(aPT.operations, bPT.operations))
		if(diffs) return [
				new Difference(description:"PortType ${aPT.name}:" , type: 'portType' ,  diffs : diffs)
			]
		[]
	}

	private List<Difference> compareOperations(aOperations, bOperations) {
		def diffs = []
		diffs.addAll(compare(aOperations, bOperations,
				{ new Difference(description:"Operation ${it.name} removed.", breaks:true, type:'operation') },
				{ new Difference(description:"Operation ${it.name} added.", safe:true, type:'operation') }))

		def opNames = aOperations.name.intersect(bOperations.name)
		opNames.each{ opName ->
			//TODO Test if input/output name matches.
			Operation aOperation = aOperations.find{it.name == opName}
			Operation bOperation = bOperations.find{it.name == opName}
			diffs.addAll(compareOperation(aOperation, bOperation))
		}
		diffs
	}

	//TODO it is not implemented yet, if an operation changes the MEP.

	private List<Difference> compareOperation(aOperation, bOperation) {
		def diffs = compareDocumentation(aOperation, bOperation)
		if(aOperation.input.name == bOperation.input.name) {
			def lDiffs = comparePortTypeMessage(aOperation.input, bOperation.input, 'input')
			diffs.addAll(lDiffs)
		} else {
			diffs << new Difference(description:"Input name has changed from ${aOperation.input.name} to ${bOperation.input.name}.", type:'input', breaks : true, exchange:'request')
		}
		if(aOperation.output?.name == bOperation.output?.name) {
			def lDiffs = comparePortTypeMessage(aOperation.output, bOperation.output, 'output')
			diffs.addAll(lDiffs)
		} else {
			diffs << new Difference(description:"Output name has changed from ${aOperation.output.name} to ${bOperation.output.name}.", type:'output', breaks : true, exchange:'response')
		}
		diffs.addAll(compareFaults(aOperation.faults, bOperation.faults, ['fault']))
		if(diffs) return [
				new Difference(description:"Operation ${aOperation.name}: ", type: 'operation', diffs: diffs)
			]
		[]
	}

	//Compare operation input/output/fault
	private List<Difference> comparePortTypeMessage(aPTM, bPTM, ptmName) {
		def exchange
		switch (ptmName) {
			case "input" : exchange = ['request'] ; break
			case "output" : exchange = ['response'] ; break
			case "fault" : exchange = ['fault'] ; break 
		}
		if(!aPTM && !bPTM) return []
		if(aPTM && !bPTM) return [
				new Difference(description:"${ptmName.capitalize()} removed.", exchange:exchange, type: ptmName)
			]
		if(!aPTM && bPTM) return [
				new Difference(description:"${ptmName.capitalize()} added.", exchange:exchange, type: ptmName)
			]
		def lDiffs = compareDocumentation(aPTM, bPTM)
		if(aPTM.message.name != bPTM.message.name || aPTM.message.namespaceUri != bPTM.message.namespaceUri) lDiffs << new Difference(description: "${ptmName.capitalize()} message has changed from ${aPTM.message.qname} to ${bPTM.message.qname}.", type: ptmName, breaks : true, exchange:exchange)
		else lDiffs.addAll(compareMessage(aPTM.message, bPTM.message, exchange))
		if(lDiffs) return [
				new Difference(description:"${ptmName.capitalize()}:", diffs: lDiffs, exchange:exchange, type: ptmName)
			]
		[]
	}

	private List<Difference> compareFaults(aFaults, bFaults, exchange) {
		def diffs = []
		def faults = aFaults.message.qname.intersect(bFaults.message.qname)
		(aFaults.message.qname - faults).each {
			diffs << new Difference(description:"Fault with message ${it} removed.", type: 'fault', exchange:exchange)
		}
		(bFaults.message.qname - faults).each {
			diffs << new Difference(description:"Fault with message ${it} added.", type: 'fault', exchange:exchange)
		}
		faults.each { f ->
			diffs.addAll(comparePortTypeMessage(aFaults.find{it.message.name == f}, bFaults.find{it.message.name == f}, exchange))
		}
		diffs
	}

	protected List<Difference> compareMessage(Message a, Message b, exchange) {
		def diffs = compareDocumentation(a, b)
		diffs.addAll( compareParts(a.parts, b.parts, exchange))
		if(diffs) return [
				new Difference(description:"Message ${a.name}:", type: 'message', diffs : diffs, exchange:exchange)
			]
		[]
	}

	private List<Difference> compareParts(aParts, bParts, exchange) {
		def diffs = []
		diffs.addAll( compare(aParts, bParts,
				{ new Difference(description:"Part ${it.name} removed." , breaks:true, exchange:exchange) },
				{ new Difference(description:"Part ${it.name} added." , breaks:true, exchange:exchange) }))
		def partNames = aParts.name.intersect(bParts.name)
		partNames.each{ ptName ->
			Part a = aParts.find{ it.name == ptName}
			Part b = bParts.find{ it.name == ptName}
			diffs.addAll(comparePart(a, b, exchange))
		}
		diffs
	}


	private List<Difference> comparePart(Part a, Part b, exchange) {
		def diffs = compareDocumentation(a, b)
		if(a.element && b.type) {
			a.element.exchange = b.type.exchange = exchange
			diffs << new Difference(description:"Element ${a.element.name} has changed to type ${b.type.qname}.", type:'element2type', breaks : true, exchange:exchange)
		}
		else if(b.element && a.type) {
			a.type.exchange = b.element.exchange = exchange
			diffs << new Difference(description:"Type ${a.type} has changed to element ${b.element.name}.", type:'type2element', breaks : true, exchange:exchange)
		}
		else if(a.element?.name != b.element?.name) {
			a.element?.exchange = b.element?.exchange = exchange
			diffs << new Difference(description:"Element has changed from ${a.element?.name} to ${b.element?.name}.", type:'element', breaks : true, exchange:exchange)
		}
		else if(a.element?.namespaceUri != b.element?.namespaceUri) {
			a.element?.exchange = b.element?.exchange = exchange
			diffs << new Difference(description:"Element namespace has changed from ${a.element.namespaceUri} to ${b.element.namespaceUri}.", type:'element', breaks : true, exchange:exchange)
		}
		else if(a.element && b.element) {
			a.element.exchange += exchange
			b.element.exchange += exchange
			diffs.addAll(new ElementDiffGenerator(a:a.element, b:b.element, generator:new SchemaDiffGenerator(compare4WSDL:true)).compare())
		}
		else if(a.type && b.type) {
			//CompareComplexType does NOT detect if a CT has changed only the namespaceURI! So the next line is needed.
			if(a.type.qname != b.type.qname) diffs << new Difference(description:"Type has changed from ${a.type.qname} to ${b.type.qname}.", type:'type', breaks : true, exchange:exchange)
			diffs.addAll(a.type.compare(new SchemaDiffGenerator(compare4WSDL:true), b.type))
		}
		if(diffs) return [new Difference(description:"Part ${a.name}: ", type: 'part', diffs : diffs, exchange:exchange)]
		[]
	}

	/*
	 * WsdlDiffGenerator doesn't compare all schema elements but the used one.
	 * So compareSchema() is not really needed!
	 */
		private List<Difference> compareSchemas(){
			def aSchemas = a.localSchemas
			def bSchemas = b.localSchemas
			def diffs = []
			(aSchemas.targetNamespace - bSchemas.targetNamespace).each { tns ->
				diffs << new Difference(description:"Schema ${tns ? tns+' ' : ''}removed." , type: 'schema')
			}
			
			(bSchemas.targetNamespace - aSchemas.targetNamespace).each { tns ->
				diffs << new Difference(description:"Schema ${tns ? tns+' ' : ''}added." , type: 'schema')
			}
			
			def schemas = aSchemas.targetNamespace.intersect(bSchemas.targetNamespace)
			schemas.each{  tns ->
				def aSchema = aSchemas.find{it.targetNamespace == tns}
				def bSchema = bSchemas.find{it.targetNamespace == tns}
				log.debug("comparing schemas with namespace ${aSchema.targetNamespace}.")
				def schemaDiffGenerator = new SchemaDiffGenerator(a:aSchema, b:bSchema)
				def lDiffs = schemaDiffGenerator.compare()
				if(lDiffs) diffs << new Difference(description:"Schema ${tns ? tns+' ' : ''}has changed:" , diffs : lDiffs, type: 'schema')
			}
			diffs
		}

	protected List<Difference> compareDocumentation(a,b){
		if(a?.documentation && !b?.documentation) return [
				new Difference(description:"Documentation removed.", breaks : false, safe : true, type: 'documentation')
			]
		if(!a?.documentation && b?.documentation) return [
				new Difference(description:"Documentation added.", breaks : false, safe : true, type: 'documentation')
			]
		if(getNormalizedContent(a?.documentation?.content) != getNormalizedContent(b?.documentation?.content))
			return [
				new Difference(description:"Documentation has changed.", breaks : false, safe : true, type: 'documentation')
			]
		[]
	}

	def getNormalizedContent(String content){
		if(!content) return
		content.replaceAll("\\s+", " ").trim()
	}

	protected def updateLabels(){
		labelTN = AbstractDiffGenerator.bundle.getString("com.predic8.wsdl.diff.labelTN")
		labelTo = AbstractDiffGenerator.bundle.getString("com.predic8.wsdl.diff.labelTo")
	}
}