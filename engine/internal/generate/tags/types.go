// Package tags independently plans typed Go struct tag edits without writing
// source files. All source positions and returned edits use UTF-16.
package tags

import "github.com/shubolab/opengoland/engine/internal/refactor/signature"

type Request struct{ signature.Request }
type Entry struct {
	Key   string `json:"key"`
	Value string `json:"value"`
}
type Field struct {
	FieldID    string   `json:"fieldId"`
	Name       string   `json:"name"`
	Names      []string `json:"names"`
	GroupID    string   `json:"groupId"`
	GroupNames []string `json:"groupNames"`
	Type       string   `json:"type"`
	Tag        string   `json:"tag"`
	Entries    []Entry  `json:"entries"`
	Exported   bool     `json:"exported"`
	Embedded   bool     `json:"embedded"`
	Editable   bool     `json:"editable"`
	Reason     string   `json:"reason,omitempty"`
}
type InspectResponse struct {
	InspectionID        string               `json:"inspectionId"`
	ConfigurationDigest string               `json:"configurationDigest"`
	StructID            string               `json:"structId"`
	Name                string               `json:"name"`
	URI                 string               `json:"uri"`
	Range               signature.Range      `json:"range"`
	Fields              []Field              `json:"fields"`
	Coverage            signature.Coverage   `json:"coverage"`
	Conflicts           []signature.Conflict `json:"conflicts"`
}
type PlanRequest struct {
	Request
	InspectionID        string            `json:"inspectionId"`
	ConfigurationDigest string            `json:"configurationDigest"`
	StructID            string            `json:"structId"`
	FieldIDs            []string          `json:"fieldIds"`
	Key                 string            `json:"key"`
	Mode                string            `json:"mode"`  // add-missing, update, remove
	Style               string            `json:"style"` // original, camel, snake, lower
	Options             []string          `json:"options,omitempty"`
	ReplaceOptions      bool              `json:"replaceOptions"`
	ValueOverrides      map[string]string `json:"valueOverrides,omitempty"`
}
type Change struct {
	FieldID string `json:"fieldId"`
	Before  string `json:"before"`
	After   string `json:"after"`
}
type PlanResponse struct {
	SymbolID            string                         `json:"symbolId"`
	InspectionID        string                         `json:"inspectionId"`
	PlanID              string                         `json:"planId"`
	PlanDigest          string                         `json:"planDigest"`
	ConfigurationDigest string                         `json:"configurationDigest"`
	StructID            string                         `json:"structId"`
	Changes             []Change                       `json:"changes"`
	Preconditions       []signature.Precondition       `json:"preconditions"`
	Directories         []signature.DirectoryCondition `json:"directories"`
	WorkspaceEdit       signature.WorkspaceEdit        `json:"workspaceEdit"`
	Usages              []signature.Usage              `json:"usages"`
	Coverage            signature.Coverage             `json:"coverage"`
	Conflicts           []signature.Conflict           `json:"conflicts"`
	Validation          signature.Validation           `json:"validation"`
}
