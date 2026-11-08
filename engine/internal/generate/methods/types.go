// Package methods independently plans typed interface implementations without
// writing source files or running the workspace's application/test binaries.
package methods

import "github.com/shubolab/opengoland/engine/internal/refactor/signature"

type Request struct {
	signature.Request
	ReceiverType           string   `json:"receiverType,omitempty"`
	NewTypeName            string   `json:"newTypeName,omitempty"`
	ReceiverKind           string   `json:"receiverKind,omitempty"` // pointer (default) or value
	InterfaceID            string   `json:"interfaceId,omitempty"`
	InterfaceExpression    string   `json:"interfaceExpression,omitempty"`
	InterfaceImportPath    string   `json:"interfaceImportPath,omitempty"`
	InterfaceTypeArguments []string `json:"interfaceTypeArguments,omitempty"`
	CatalogPackages        []string `json:"catalogPackages,omitempty"`
}

type TypeParameter struct {
	Name       string `json:"name"`
	Constraint string `json:"constraint"`
}
type Receiver struct {
	ReceiverID     string          `json:"receiverId"`
	Name           string          `json:"name"`
	URI            string          `json:"uri"`
	Range          signature.Range `json:"range"`
	TypeParameters []TypeParameter `json:"typeParameters"`
	New            bool            `json:"new"`
}
type Interface struct {
	InterfaceID        string          `json:"interfaceId"`
	Name               string          `json:"name"`
	PackagePath        string          `json:"packagePath"`
	PackageName        string          `json:"packageName"`
	Display            string          `json:"display"`
	TypeParameters     []TypeParameter `json:"typeParameters"`
	MethodCount        int             `json:"methodCount"`
	NeedsTypeArguments bool            `json:"needsTypeArguments"`
}
type Method struct {
	MethodID  string `json:"methodId"`
	Name      string `json:"name"`
	Signature string `json:"signature"`
	Status    string `json:"status"` // missing, existing, conflict
	Message   string `json:"message,omitempty"`
}
type InspectResponse struct {
	InspectionID        string               `json:"inspectionId"`
	ConfigurationDigest string               `json:"configurationDigest"`
	EntryKind           string               `json:"entryKind"` // receiver or interface
	ReceiverKind        string               `json:"receiverKind"`
	Receiver            *Receiver            `json:"receiver,omitempty"`
	ReceiverCandidates  []Receiver           `json:"receiverCandidates"`
	Interfaces          []Interface          `json:"interfaces"`
	SelectedInterface   *Interface           `json:"selectedInterface,omitempty"`
	Methods             []Method             `json:"methods"`
	Implemented         bool                 `json:"implemented"`
	Coverage            signature.Coverage   `json:"coverage"`
	Conflicts           []signature.Conflict `json:"conflicts"`
}
type PlanRequest struct {
	Request
	InspectionID        string   `json:"inspectionId"`
	ConfigurationDigest string   `json:"configurationDigest"`
	ReceiverID          string   `json:"receiverId"`
	ReceiverName        string   `json:"receiverName,omitempty"`
	MethodIDs           []string `json:"methodIds,omitempty"`
	AllMissing          bool     `json:"allMissing"`
}
type PlanResponse struct {
	SymbolID            string                         `json:"symbolId"`
	InspectionID        string                         `json:"inspectionId"`
	PlanID              string                         `json:"planId"`
	PlanDigest          string                         `json:"planDigest"`
	ConfigurationDigest string                         `json:"configurationDigest"`
	Receiver            *Receiver                      `json:"receiver,omitempty"`
	Implemented         bool                           `json:"implemented"`
	GeneratedMethods    []Method                       `json:"generatedMethods"`
	Preconditions       []signature.Precondition       `json:"preconditions"`
	Directories         []signature.DirectoryCondition `json:"directories"`
	WorkspaceEdit       signature.WorkspaceEdit        `json:"workspaceEdit"`
	Usages              []signature.Usage              `json:"usages"`
	Coverage            signature.Coverage             `json:"coverage"`
	Conflicts           []signature.Conflict           `json:"conflicts"`
	Validation          signature.Validation           `json:"validation"`
}
