module example.invalid/open-goland/consistency/one

go 1.26.0

require (
	example.invalid/open-goland/consistency/external v0.0.0
	example.invalid/open-goland/consistency/two v0.0.0
)

replace example.invalid/open-goland/consistency/external => ../../external

replace example.invalid/open-goland/consistency/two => ../../two-fallback
