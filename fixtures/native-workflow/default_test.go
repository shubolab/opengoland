//go:build !native_extra

package nativev6

import ("fmt"; "testing")

func TestDefaultContext(t *testing.T) { fmt.Println("默认上下文 中文😀"); t.Log("default native context") }
