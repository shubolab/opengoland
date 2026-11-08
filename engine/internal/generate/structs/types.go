// Package structs independently plans typed constructors and field accessors.
// It returns immutable workspace edits and never runs or writes user programs.
package structs

import "github.com/shubolab/opengoland/engine/internal/refactor/signature"

type Request struct{ signature.Request }
type TypeParameter struct {
	Name       string `json:"name"`
	Constraint string `json:"constraint"`
}
type Field struct {
	FieldID        string   `json:"fieldId"`
	Name           string   `json:"name"`
	Names          []string `json:"names"`
	Type           string   `json:"type"`
	Exported       bool     `json:"exported"`
	Embedded       bool     `json:"embedded"`
	Editable       bool     `json:"editable"`
	Reason         string   `json:"reason,omitempty"`
	ParameterName  string   `json:"parameterName"`
	GetterName     string   `json:"getterName"`
	SetterName     string   `json:"setterName"`
	GetterConflict string   `json:"getterConflict,omitempty"`
	SetterConflict string   `json:"setterConflict,omitempty"`
}
type InspectResponse struct {
	InspectionID        string               `json:"inspectionId"`
	ConfigurationDigest string               `json:"configurationDigest"`
	StructID            string               `json:"structId"`
	Name                string               `json:"name"`
	URI                 string               `json:"uri"`
	Range               signature.Range      `json:"range"`
	TypeParameters      []TypeParameter      `json:"typeParameters"`
	ConstructorName     string               `json:"constructorName"`
	ConstructorConflict string               `json:"constructorConflict,omitempty"`
	CaretFieldID        string               `json:"caretFieldId,omitempty"`
	Fields              []Field              `json:"fields"`
	Coverage            signature.Coverage   `json:"coverage"`
	Conflicts           []signature.Conflict `json:"conflicts"`
}
type Selection struct {
	FieldID       string `json:"fieldId"`
	ParameterName string `json:"parameterName,omitempty"`
	GetterName    string `json:"getterName,omitempty"`
	SetterName    string `json:"setterName,omitempty"`
}
type PlanRequest struct {
	Request
	InspectionID        string      `json:"inspectionId"`
	ConfigurationDigest string      `json:"configurationDigest"`
	StructID            string      `json:"structId"`
	Kind                string      `json:"kind"` // constructor, getter, setter, both
	ConstructorName     string      `json:"constructorName,omitempty"`
	ConstructorKind     string      `json:"constructorKind,omitempty"` // pointer (default), value
	GetterKind          string      `json:"getterKind,omitempty"`      // pointer (default), value
	ReceiverName        string      `json:"receiverName,omitempty"`
	Selections          []Selection `json:"selections"`
}
type Generated struct {
	Kind    string `json:"kind"`
	Name    string `json:"name"`
	FieldID string `json:"fieldId,omitempty"`
}
type PlanResponse struct {
	SymbolID            string                         `json:"symbolId"`
	InspectionID        string                         `json:"inspectionId"`
	PlanID              string                         `json:"planId"`
	PlanDigest          string                         `json:"planDigest"`
	ConfigurationDigest string                         `json:"configurationDigest"`
	StructID            string                         `json:"structId"`
	Generated           []Generated                    `json:"generated"`
	Preconditions       []signature.Precondition       `json:"preconditions"`
	Directories         []signature.DirectoryCondition `json:"directories"`
	WorkspaceEdit       signature.WorkspaceEdit        `json:"workspaceEdit"`
	Usages              []signature.Usage              `json:"usages"`
	Coverage            signature.Coverage             `json:"coverage"`
	Conflicts           []signature.Conflict           `json:"conflicts"`
	Validation          signature.Validation           `json:"validation"`
}
