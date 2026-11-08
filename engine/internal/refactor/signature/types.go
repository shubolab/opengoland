// Package signature independently plans typed Go signature refactorings without writing source files.
package signature

type Position struct {
	Line      int `json:"line"`
	Character int `json:"character"`
}
type Range struct {
	Start Position `json:"start"`
	End   Position `json:"end"`
}
type Target struct {
	URI      string   `json:"uri"`
	Position Position `json:"position"`
}
type LSPVersion struct {
	Session int64 `json:"session"`
	Version int   `json:"version"`
}
type Overlay struct {
	URI           string      `json:"uri"`
	Text          string      `json:"text"`
	DocumentStamp int64       `json:"documentStamp"`
	DiskSHA256    string      `json:"diskSha256,omitempty"`
	LSP           *LSPVersion `json:"lsp,omitempty"`
}
type Request struct {
	OperationID  string            `json:"operationId"`
	Root         string            `json:"root,omitempty"`
	GoExecutable string            `json:"goExecutable,omitempty"`
	Environment  map[string]string `json:"environment,omitempty"`
	BuildTags    []string          `json:"buildTags,omitempty"`
	Target       Target            `json:"target"`
	Overlays     []Overlay         `json:"overlays,omitempty"`
}
type Expression struct {
	Kind string `json:"kind"`
	Text string `json:"text,omitempty"`
}
type Row struct {
	RowID           string      `json:"rowId"`
	OldIndex        *int        `json:"oldIndex"`
	Name            string      `json:"name"`
	Type            string      `json:"type"`
	Variadic        bool        `json:"variadic,omitempty"`
	DefaultArgument *Expression `json:"defaultArgument,omitempty"`
	BodyDefault     *Expression `json:"bodyDefault,omitempty"`
}
type Migration struct {
	RowID      string `json:"rowId"`
	Expression string `json:"expression"`
}
type Change struct {
	Name                       string      `json:"name"`
	Parameters                 []Row       `json:"parameters"`
	Results                    []Row       `json:"results"`
	ParameterBodyMigrations    []Migration `json:"parameterBodyMigrations,omitempty"`
	RemovedResultUseMigrations []Migration `json:"removedResultUseMigrations,omitempty"`
}
type Conflict struct {
	URI     string `json:"uri"`
	Range   Range  `json:"range"`
	Code    string `json:"code"`
	Message string `json:"message"`
}
type Usage struct {
	URI     string `json:"uri"`
	Range   Range  `json:"range"`
	Kind    string `json:"kind"`
	Message string `json:"message"`
}
type Coverage struct {
	Modules          []string `json:"modules"`
	Packages         int      `json:"packages"`
	Files            int      `json:"files"`
	Tests            bool     `json:"tests"`
	ExternalBoundary bool     `json:"externalBoundary"`
	BuildContext     string   `json:"buildContext"`
}
type InspectResponse struct {
	SymbolID            string     `json:"symbolId"`
	ConfigurationDigest string     `json:"configurationDigest"`
	Name                string     `json:"name"`
	Kind                string     `json:"kind"`
	URI                 string     `json:"uri"`
	Range               Range      `json:"range"`
	Receiver            string     `json:"receiver,omitempty"`
	Parameters          []Row      `json:"parameters"`
	Results             []Row      `json:"results"`
	RelatedDeclarations int        `json:"relatedDeclarations"`
	FunctionValues      int        `json:"functionValues"`
	Coverage            Coverage   `json:"coverage"`
	Conflicts           []Conflict `json:"conflicts"`
}
type PlanRequest struct {
	Request
	SymbolID            string `json:"symbolId"`
	ConfigurationDigest string `json:"configurationDigest"`
	Change              Change `json:"change"`
}
type Precondition struct {
	Exists        bool        `json:"exists"`
	URI           string      `json:"uri"`
	DiskSHA256    string      `json:"diskSha256"`
	TextSHA256    string      `json:"textSha256"`
	DocumentStamp *int64      `json:"documentStamp,omitempty"`
	LSP           *LSPVersion `json:"lsp,omitempty"`
}
type TextEdit struct {
	Range   Range  `json:"range"`
	NewText string `json:"newText"`
}
type TextDocument struct {
	URI     string `json:"uri"`
	Version *int   `json:"version"`
}
type DocumentEdit struct {
	TextDocument TextDocument `json:"textDocument"`
	Edits        []TextEdit   `json:"edits"`
}
type WorkspaceEdit struct {
	DocumentChanges []DocumentEdit `json:"documentChanges"`
}
type Validation struct {
	BeforeErrors []string `json:"beforeErrors"`
	AfterErrors  []string `json:"afterErrors"`
	TypeChecked  bool     `json:"typeChecked"`
}
type PlanResponse struct {
	SymbolID            string               `json:"symbolId"`
	PlanID              string               `json:"planId"`
	PlanDigest          string               `json:"planDigest"`
	ConfigurationDigest string               `json:"configurationDigest"`
	Preconditions       []Precondition       `json:"preconditions"`
	Directories         []DirectoryCondition `json:"directories"`
	WorkspaceEdit       WorkspaceEdit        `json:"workspaceEdit"`
	Usages              []Usage              `json:"usages"`
	Coverage            Coverage             `json:"coverage"`
	Conflicts           []Conflict           `json:"conflicts"`
	Validation          Validation           `json:"validation"`
}

// ByteEdit uses UTF-8 byte offsets in one immutable source file, not LSP positions.
type ByteEdit struct {
	Start   int
	End     int
	NewText string
}
