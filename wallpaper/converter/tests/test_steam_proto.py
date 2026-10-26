from converter import steam


def test_fields_round_trip():
    message = (steam._field(1, 76561197960265728) + steam._field(2, "s.team/q/1")
               + steam._field(3, b"\x00\xff") + steam._fixed64(4, 76561197960265728)
               + steam._field(5, -500))
    fields = steam._decode(message)
    assert fields[1] == 76561197960265728
    assert steam._text(fields, 2) == "s.team/q/1"
    assert fields[3] == b"\x00\xff"
    assert fields[4] == 76561197960265728
    assert fields[5] == (-500) & 0xFFFFFFFFFFFFFFFF


def test_varint_matches_protobuf_spec():
    assert steam._varint(1) == b"\x01"
    assert steam._varint(300) == b"\xac\x02"
