// Package native validates and names native Go refactoring candidates before a
// single editor transaction. It never writes source or executes user programs.
package native

import "github.com/shubolab/opengoland/engine/internal/refactor/signature"

// Request binds a server-produced candidate to the independently guarded source
// snapshot. With no candidateEdit, Inspect returns typed caret/selection choices.
type Request struct {
	signature.Request
	Kind                  string                  `json:"kind"`
	CandidateKind         string                  `json:"candidateKind,omitempty"`
	CandidateSourceSHA256 string                  `json:"candidateSourceSha256,omitempty"`
	ExpectedSourceDigest  string                  `json:"expectedSourceDigest,omitempty"`
	Selection             signature.Range         `json:"selection"`
	CandidateEdit         signature.WorkspaceEdit `json:"candidateEdit"`
}

type TargetChoice struct {
	TargetID string          `json:"targetId"`
	URI      string          `json:"uri"`
	Range    signature.Range `json:"range"`
	Text     string          `json:"text"`
	Kind     string          `json:"kind"`
}

type Name struct {
	NameID        string `json:"nameId"`
	GeneratedName string `json:"generatedName"`
	SuggestedName string `json:"suggestedName"`
	Type          string `json:"type"`
}

type InspectResponse struct {
	InspectionID        string               `json:"inspectionId"`
	ConfigurationDigest string               `json:"configurationDigest"`
	SourceDigest        string               `json:"sourceDigest"`
	Kind                string               `json:"kind"`
	Targets             []TargetChoice       `json:"targets"`
	Names               []Name               `json:"names"`
	Signature           string               `json:"signature"`
	Occurrences         int                  `json:"occurrences"`
	Inline              *InlineDetails       `json:"inline,omitempty"`
	Coverage            signature.Coverage   `json:"coverage"`
	Conflicts           []signature.Conflict `json:"conflicts"`
}

type InlineDetails struct {
	Name          string `json:"name"`
	References    int    `json:"references"`
	CanKeep       bool   `json:"canKeep"`
	CanRemove     bool   `json:"canRemove"`
	RemovalReason string `json:"removalReason,omitempty"`
	All           bool   `json:"all"`
}

type NameSelection struct {
	NameID string `json:"nameId"`
	Name   string `json:"name"`
}

type PlanRequest struct {
	Request
	InspectionID        string          `json:"inspectionId"`
	ConfigurationDigest string          `json:"configurationDigest"`
	Names               []NameSelection `json:"names"`
	InlineDeclaration   string          `json:"inlineDeclaration,omitempty"` // keep or remove
}

type Focus struct {
	URI   string          `json:"uri"`
	Range signature.Range `json:"range"`
}

type PlanResponse struct {
	signature.PlanResponse
	InspectionID string `json:"inspectionId"`
	Kind         string `json:"kind"`
	Focus        *Focus `json:"focus,omitempty"`
}
