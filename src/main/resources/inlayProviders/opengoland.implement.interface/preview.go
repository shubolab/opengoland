package preview

type Reader interface {
    Read(buffer []byte) (count int, err error)
}
