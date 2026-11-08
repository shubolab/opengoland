// Package tests plans independently implemented, typed Go test scaffolds. It
// returns previewable edits and never writes or executes the user's Go code.
package tests

import "github.com/shubolab/opengoland/engine/internal/refactor/signature"

type Scope string

const (
	ScopeEmpty    Scope = "empty"
	ScopeFunction Scope = "function"
	ScopeFile     Scope = "file"
	ScopePackage  Scope = "package"
)

type PackageMode string

const (
	PackageSame     PackageMode = "same"
	PackageExternal PackageMode = "external"
)

// Request uses the same UTF-16 target and versioned dirty overlays as signature
// refactoring. Scope is relative to target's active Go package, not recursive.
type Request struct {
	signature.Request
	Scope       Scope       `json:"scope"`
	PackageMode PackageMode `json:"packageMode"`
}

type Parameter struct {
	Name     string `json:"name"`
	Type     string `json:"type"`
	Variadic bool   `json:"variadic,omitempty"`
}
type TypeParameter struct {
	Name       string `json:"name"`
	Constraint string `json:"constraint"`
}
type Symbol struct {
	SymbolID           string          `json:"symbolId"`
	Name               string          `json:"name"`
	Kind               string          `json:"kind"`
	URI                string          `json:"uri"`
	Range              signature.Range `json:"range"`
	Receiver           string          `json:"receiver,omitempty"`
	Parameters         []Parameter     `json:"parameters"`
	Results            []Parameter     `json:"results"`
	TypeParameters     []TypeParameter `json:"typeParameters"`
	SuggestedTestName  string          `json:"suggestedTestName"`
	DestinationURI     string          `json:"destinationUri"`
	ExistingTest       bool            `json:"existingTest"`
	Exported           bool            `json:"exported"`
	NeedsTypeArguments bool            `json:"needsTypeArguments"`
}

type InspectResponse struct {
	InspectionID          string               `json:"inspectionId"`
	ConfigurationDigest   string               `json:"configurationDigest"`
	Scope                 Scope                `json:"scope"`
	PackageMode           PackageMode          `json:"packageMode"`
	PackageName           string               `json:"packageName"`
	PackagePath           string               `json:"packagePath"`
	DefaultDestinationURI string               `json:"defaultDestinationUri"`
	Symbols               []Symbol             `json:"symbols"`
	Coverage              signature.Coverage   `json:"coverage"`
	Conflicts             []signature.Conflict `json:"conflicts"`
}

type Selection struct {
	SymbolID      string   `json:"symbolId"`
	TestName      string   `json:"testName,omitempty"`
	TypeArguments []string `json:"typeArguments,omitempty"`
}
type PlanRequest struct {
	Request
	InspectionID        string      `json:"inspectionId"`
	ConfigurationDigest string      `json:"configurationDigest"`
	Selections          []Selection `json:"selections"`
	// Empty uses emptyName. Other scopes use the selected symbols; an empty
	// selection means none, rather than silently generating every declaration.
	EmptyName string `json:"emptyName,omitempty"`
	// Optional single destination in the selected package. Without it each
	// source file uses its corresponding _test.go, retaining platform suffixes.
	DestinationURI string `json:"destinationUri,omitempty"`
}

// WorkspaceEdit is standard LSP. New files have a CreateFile resource operation
// followed by a TextDocumentEdit; existing files carry the captured LSP version.
type WorkspaceEdit struct {
	DocumentChanges []any `json:"documentChanges"`
}
type CreateFile struct {
	Kind string `json:"kind"`
	URI  string `json:"uri"`
}
type PlanResponse struct {
	SymbolID            string                         `json:"symbolId"`
	InspectionID        string                         `json:"inspectionId"`
	PlanID              string                         `json:"planId"`
	PlanDigest          string                         `json:"planDigest"`
	ConfigurationDigest string                         `json:"configurationDigest"`
	Preconditions       []signature.Precondition       `json:"preconditions"`
	Directories         []signature.DirectoryCondition `json:"directories"`
	WorkspaceEdit       WorkspaceEdit                  `json:"workspaceEdit"`
	Usages              []signature.Usage              `json:"usages"`
	Coverage            signature.Coverage             `json:"coverage"`
	Conflicts           []signature.Conflict           `json:"conflicts"`
	Validation          signature.Validation           `json:"validation"`
}
