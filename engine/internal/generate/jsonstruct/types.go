// Package jsonstruct independently infers Go types from JSON samples and returns
// typed, guarded edit plans. Samples do not describe all future JSON values.
package jsonstruct

import "github.com/shubolab/opengoland/engine/internal/refactor/signature"

type Request struct {
	signature.Request
	Mode          string `json:"mode"`
	JSONText      string `json:"jsonText"`
	TypeName      string `json:"typeName,omitempty"`
	NumericPolicy string `json:"numericPolicy,omitempty"`
}
type Field struct {
	FieldID  string `json:"fieldId"`
	JSONKey  string `json:"jsonKey"`
	Name     string `json:"name"`
	Type     string `json:"type"`
	Optional bool   `json:"optional"`
	Nullable bool   `json:"nullable"`
	Editable bool   `json:"editable"`
	Reason   string `json:"reason,omitempty"`
}
type Declaration struct {
	Name string `json:"name"`
	Kind string `json:"kind"`
	Type string `json:"type"`
}
type Warning struct {
	Path    string `json:"path"`
	Code    string `json:"code"`
	Message string `json:"message"`
}
type InspectResponse struct {
	InspectionID        string               `json:"inspectionId"`
	ConfigurationDigest string               `json:"configurationDigest"`
	Mode                string               `json:"mode"`
	StructID            string               `json:"structId"`
	Name                string               `json:"name"`
	TypeName            string               `json:"typeName"`
	URI                 string               `json:"uri"`
	Range               signature.Range      `json:"range"`
	RootType            string               `json:"rootType"`
	Fields              []Field              `json:"fields"`
	Declarations        []Declaration        `json:"declarations"`
	Preview             string               `json:"preview"`
	Warnings            []Warning            `json:"warnings"`
	Coverage            signature.Coverage   `json:"coverage"`
	Conflicts           []signature.Conflict `json:"conflicts"`
}
type PlanRequest struct {
	Request
	InspectionID        string   `json:"inspectionId"`
	ConfigurationDigest string   `json:"configurationDigest"`
	StructID            string   `json:"structId"`
	FieldIDs            []string `json:"fieldIds,omitempty"`
}
type PlanResponse struct {
	SymbolID            string                         `json:"symbolId"`
	InspectionID        string                         `json:"inspectionId"`
	PlanID              string                         `json:"planId"`
	PlanDigest          string                         `json:"planDigest"`
	ConfigurationDigest string                         `json:"configurationDigest"`
	StructID            string                         `json:"structId"`
	RootType            string                         `json:"rootType"`
	Declarations        []Declaration                  `json:"declarations"`
	Warnings            []Warning                      `json:"warnings"`
	Preconditions       []signature.Precondition       `json:"preconditions"`
	Directories         []signature.DirectoryCondition `json:"directories"`
	WorkspaceEdit       signature.WorkspaceEdit        `json:"workspaceEdit"`
	Usages              []signature.Usage              `json:"usages"`
	Coverage            signature.Coverage             `json:"coverage"`
	Conflicts           []signature.Conflict           `json:"conflicts"`
	Validation          signature.Validation           `json:"validation"`
}
