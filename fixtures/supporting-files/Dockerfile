FROM golang:1.26 AS build
WORKDIR /src
COPY . .
RUN go build -o /out/demo .
FROM gcr.io/distroless/static-debian12
COPY --from=build /out/demo /demo
ENTRYPOINT ["/demo"]
