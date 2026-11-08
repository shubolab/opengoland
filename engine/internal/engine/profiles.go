package engine

import (
	"compress/gzip"
	"context"
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strings"

	pprof "github.com/google/pprof/profile"
)

type ProfileOptions struct {
	Kind    string `json:"kind"`
	Mode    string `json:"mode"`
	Target  string `json:"target"`
	Pattern string `json:"pattern"`
}

type ProfileArtifact struct {
	Path      string `json:"path"`
	Kind      string `json:"kind"`
	Binary    string `json:"binary"`
	directory string
}

func (m *Manager) prepareProfile(options *ProfileOptions, args []string) ([]string, *ProfileArtifact, error) {
	if options == nil {
		return nil, nil, errors.New("profile requires kind, mode and one package target")
	}
	flags := map[string][]string{
		"cpu": {"-cpuprofile="}, "memory": {"-memprofile=", "-memprofilerate=1"},
		"block": {"-blockprofile=", "-blockprofilerate=1"}, "mutex": {"-mutexprofile=", "-mutexprofilefraction=1"},
	}
	selected, ok := flags[options.Kind]
	if !ok {
		return nil, nil, errors.New("profile kind must be cpu, memory, block or mutex")
	}
	if options.Mode != "test" && options.Mode != "benchmark" {
		return nil, nil, errors.New("profile mode must be test or benchmark")
	}
	target := options.Target
	if target == "" {
		target = "."
	}
	if strings.ContainsRune(target+options.Pattern, '\x00') || strings.HasPrefix(target, "-") || strings.Contains(target, "...") {
		return nil, nil, errors.New("profile one concrete Go package; recursive package patterns are not supported")
	}
	for _, arg := range args {
		name := strings.TrimPrefix(strings.TrimLeft(strings.SplitN(arg, "=", 2)[0], "-"), "test.")
		switch name {
		case "json", "c", "o", "exec", "args", "cpuprofile", "memprofile", "blockprofile", "mutexprofile", "outputdir":
			return nil, nil, fmt.Errorf("profile output and launch flag -%s is managed by the engine", name)
		}
	}
	base := filepath.Join(filepath.Dir(m.cacheRoot), "profiles")
	if err := os.MkdirAll(base, 0700); err != nil {
		return nil, nil, err
	}
	directory, err := os.MkdirTemp(base, "session-")
	if err != nil {
		return nil, nil, err
	}
	artifact := &ProfileArtifact{Path: filepath.Join(directory, options.Kind+".pprof"), Kind: options.Kind, Binary: filepath.Join(directory, "profile.test"), directory: directory}
	prefix := []string{"test", "-json", "-count=1", "-o=" + artifact.Binary, selected[0] + artifact.Path}
	prefix = append(prefix, selected[1:]...)
	if options.Mode == "benchmark" {
		pattern := options.Pattern
		if pattern == "" {
			pattern = "."
		}
		prefix = append(prefix, "-run=^$", "-bench="+pattern)
	} else if options.Pattern != "" {
		prefix = append(prefix, "-run="+options.Pattern)
	}
	prefix = append(prefix, args...)
	prefix = append(prefix, target)
	return prefix, artifact, nil
}

type ProfileInspectRequest struct {
	Path        string `json:"path"`
	SampleIndex *int   `json:"sampleIndex,omitempty"`
	Limit       int    `json:"limit,omitempty"`
	Sort        string `json:"sort,omitempty"`
}
type ProfileMetric struct {
	Type  string `json:"type"`
	Unit  string `json:"unit"`
	Total int64  `json:"total"`
}
type ProfileHotspot struct {
	Name              string  `json:"name"`
	SystemName        string  `json:"systemName,omitempty"`
	File              string  `json:"file,omitempty"`
	Line              int64   `json:"line"`
	Flat              int64   `json:"flat"`
	Cumulative        int64   `json:"cumulative"`
	FlatPercent       float64 `json:"flatPercent"`
	CumulativePercent float64 `json:"cumulativePercent"`
}
type ProfileReport struct {
	Path          string           `json:"path"`
	SampleTypes   []ProfileMetric  `json:"sampleTypes"`
	SampleIndex   int              `json:"sampleIndex"`
	SampleCount   int              `json:"sampleCount"`
	DurationNanos int64            `json:"durationNanos"`
	TimeNanos     int64            `json:"timeNanos"`
	FunctionCount int              `json:"functionCount"`
	Rows          []ProfileHotspot `json:"rows"`
	Truncated     bool             `json:"truncated"`
	Warnings      []string         `json:"warnings"`
}

// Bound both the compressed file and its expansion before passing it to the official parser.
const maxProfileBytes = 64 << 20

func readProfile(path string) (*pprof.Profile, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	stat, err := file.Stat()
	if err != nil {
		return nil, err
	}
	if !stat.Mode().IsRegular() || stat.Size() > maxProfileBytes {
		return nil, errors.New("profile must be a regular file no larger than 64 MiB")
	}
	header := make([]byte, 2)
	n, _ := file.ReadAt(header, 0)
	var reader io.Reader = file
	if n == 2 && header[0] == 0x1f && header[1] == 0x8b {
		compressed, err := gzip.NewReader(file)
		if err != nil {
			return nil, err
		}
		defer compressed.Close()
		reader = compressed
	}
	data, err := io.ReadAll(io.LimitReader(reader, maxProfileBytes+1))
	if err != nil {
		return nil, err
	}
	if len(data) > maxProfileBytes {
		return nil, errors.New("expanded profile exceeds 64 MiB")
	}
	parsed, err := pprof.ParseUncompressed(data)
	if err != nil {
		return nil, fmt.Errorf("invalid pprof protobuf: %w", err)
	}
	if err = parsed.CheckValid(); err != nil {
		return nil, fmt.Errorf("invalid profile: %w", err)
	}
	return parsed, nil
}

func (m *Manager) InspectProfile(ctx context.Context, req ProfileInspectRequest) (ProfileReport, error) {
	if req.Path == "" || strings.ContainsRune(req.Path, '\x00') {
		return ProfileReport{}, errors.New("profile/inspect requires a profile file path")
	}
	path, err := filepath.Abs(req.Path)
	if err != nil {
		return ProfileReport{}, err
	}
	parsed, err := readProfile(path)
	if err != nil {
		return ProfileReport{}, err
	}
	// Match pprof's standard DropFrames/KeepFrames policy using the official implementation.
	if err := parsed.RemoveUninteresting(); err != nil {
		return ProfileReport{}, err
	}
	report, err := summarizeProfile(ctx, parsed, req)
	report.Path = path
	return report, err
}

func summarizeProfile(ctx context.Context, p *pprof.Profile, req ProfileInspectRequest) (ProfileReport, error) {
	report := ProfileReport{SampleTypes: []ProfileMetric{}, Rows: []ProfileHotspot{}, Warnings: []string{}, SampleCount: len(p.Sample), DurationNanos: p.DurationNanos, TimeNanos: p.TimeNanos}
	if len(p.SampleType) == 0 {
		return report, errors.New("profile has no sample types")
	}
	index := len(p.SampleType) - 1
	for i, value := range p.SampleType {
		report.SampleTypes = append(report.SampleTypes, ProfileMetric{Type: value.Type, Unit: value.Unit})
		if value.Type == p.DefaultSampleType {
			index = i
		}
	}
	if req.SampleIndex != nil {
		index = *req.SampleIndex
	}
	if index < 0 || index >= len(p.SampleType) {
		return report, errors.New("sampleIndex is outside this profile's sample types")
	}
	if req.Sort != "" && req.Sort != "flat" && req.Sort != "cumulative" {
		return report, errors.New("profile sort must be flat or cumulative")
	}
	report.SampleIndex = index
	rows := map[string]*ProfileHotspot{}
	keyFor := func(f *pprof.Function) string {
		return fmt.Sprintf("%q%q%q%d", f.Name, f.SystemName, f.Filename, f.StartLine)
	}
	for _, sample := range p.Sample {
		if err := ctx.Err(); err != nil {
			return report, err
		}
		for i, value := range sample.Value {
			if addOverflows(report.SampleTypes[i].Total, value) {
				return report, errors.New("profile sample total overflows int64")
			}
			report.SampleTypes[i].Total += value
		}
		seen := map[string]bool{}
		leaf := true
		for _, location := range sample.Location {
			frames := location.Line
			if len(frames) == 0 {
				frames = []pprof.Line{{Function: &pprof.Function{Name: fmt.Sprintf("0x%x", location.Address)}}}
			}
			for _, frame := range frames {
				function := frame.Function
				if function == nil {
					continue
				}
				key := keyFor(function)
				row := rows[key]
				if row == nil {
					line := function.StartLine
					if line <= 0 {
						line = frame.Line
					}
					row = &ProfileHotspot{Name: function.Name, SystemName: function.SystemName, File: function.Filename, Line: line}
					rows[key] = row
				}
				value := sample.Value[index]
				if !seen[key] {
					if addOverflows(row.Cumulative, value) {
						return report, errors.New("profile cumulative value overflows int64")
					}
					row.Cumulative += value
					seen[key] = true
				}
				if leaf {
					if addOverflows(row.Flat, value) {
						return report, errors.New("profile flat value overflows int64")
					}
					row.Flat += value
					leaf = false
				}
			}
		}
	}
	total := report.SampleTypes[index].Total
	for _, row := range rows {
		if row.Flat == 0 && row.Cumulative == 0 {
			continue
		}
		if total != 0 {
			row.FlatPercent = float64(row.Flat) / float64(total) * 100
			row.CumulativePercent = float64(row.Cumulative) / float64(total) * 100
		}
		report.Rows = append(report.Rows, *row)
	}
	sort.Slice(report.Rows, func(i, j int) bool {
		a, b := report.Rows[i], report.Rows[j]
		if req.Sort == "cumulative" {
			if a.Cumulative != b.Cumulative {
				return a.Cumulative > b.Cumulative
			}
		} else if a.Flat != b.Flat {
			return a.Flat > b.Flat
		}
		if a.Cumulative != b.Cumulative {
			return a.Cumulative > b.Cumulative
		}
		return a.Name+a.File < b.Name+b.File
	})
	report.FunctionCount = len(report.Rows)
	limit := req.Limit
	if limit <= 0 {
		limit = 2000
	}
	if limit > 5000 {
		limit = 5000
	}
	if len(report.Rows) > limit {
		report.Rows = report.Rows[:limit]
		report.Truncated = true
		report.Warnings = append(report.Warnings, "Hotspot rows were limited; choose a different metric or sort to inspect other hotspots.")
	}
	if len(p.Sample) == 0 {
		report.Warnings = append(report.Warnings, "No samples were recorded; run a longer workload or benchmark.")
	}
	for _, row := range report.Rows {
		if row.File == "" || row.Line <= 0 {
			report.Warnings = append(report.Warnings, "Some frames have no source location in the profile; source navigation is unavailable for those frames.")
			break
		}
	}
	return report, nil
}
func addOverflows(a, b int64) bool {
	return b > 0 && a > math.MaxInt64-b || b < 0 && a < math.MinInt64-b
}
