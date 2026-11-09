// Package typesearch builds the native Go type-search catalog from Go metadata
// and source declarations, independently of bounded workspace/symbol queries.
package typesearch

import "github.com/shubolab/opengoland/engine/internal/refactor/signature"

type Request struct {
	signature.Request
	IncludeDependencies bool `json:"includeDependencies"`
	IncludeSDK          bool `json:"includeSdk"`
}

type Type struct {
	Name           string          `json:"name"`
	PackagePath    string          `json:"packagePath"`
	PackageName    string          `json:"packageName"`
	URI            string          `json:"uri"`
	Range          signature.Range `json:"range"`
	SelectionRange signature.Range `json:"selectionRange"`
	Kind           string          `json:"kind"`
	Origin         string          `json:"origin"`
}

type Issue struct {
	Code    string `json:"code"`
	Message string `json:"message"`
	URI     string `json:"uri,omitempty"`
}

type Coverage struct {
	Modules             []string `json:"modules"`
	Packages            int      `json:"packages"`
	Files               int      `json:"files"`
	Tests               bool     `json:"tests"`
	IncludeDependencies bool     `json:"includeDependencies"`
	IncludeSDK          bool     `json:"includeSdk"`
	BuildContext        string   `json:"buildContext"`
	Complete            bool     `json:"complete"`
}

type Response struct {
	CatalogID           string                         `json:"catalogId"`
	ConfigurationDigest string                         `json:"configurationDigest"`
	Types               []Type                         `json:"types"`
	SourceGuards        []signature.Precondition       `json:"sourceGuards"`
	Directories         []signature.DirectoryCondition `json:"directories"`
	Coverage            Coverage                       `json:"coverage"`
	Issues              []Issue                        `json:"issues"`
}
