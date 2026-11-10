//go:build native_extra

package nativev6

import ("fmt"; "testing")

func TestExtraContext(t *testing.T) { fmt.Println("额外上下文 中文😀"); t.Log("extra native context") }
