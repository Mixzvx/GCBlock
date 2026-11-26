"""Minimal Android binary XML (AXML) writer.

Supports exactly what the LSPosed module manifest needs: namespace, element
nesting, attributes with string / int / boolean typed values.
"""
import struct

RES_XML_TYPE = 0x0003
RES_STRING_POOL = 0x0001
RES_XML_RESOURCE_MAP = 0x0180
RES_XML_START_NAMESPACE = 0x0100
RES_XML_END_NAMESPACE = 0x0101
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103

TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12

# android.R.attr ids (public)
ATTR_IDS = {
    'name': 0x01010003,
    'value': 0x01010024,
    'label': 0x01010001,
    'hasCode': 0x0101000c,
    'versionCode': 0x0101021b,
    'versionName': 0x0101021c,
    'minSdkVersion': 0x0101020c,
    'targetSdkVersion': 0x01010270,
    'description': 0x01010020,
    'enabled': 0x0101000e,
    'exported': 0x01010010,
}


class Attr:
    def __init__(self, ns_prefix, name, kind, value):
        self.ns_prefix = ns_prefix      # 'android' or '' (none)
        self.name = name
        self.kind = kind                # 'str' | 'int' | 'bool'
        self.value = value


class Element:
    def __init__(self, name, attrs=None, children=None):
        self.name = name
        self.attrs = attrs or []
        self.children = children or []


class Writer:
    def __init__(self):
        self.strings = []
        self.index = {}

    def s(self, value):
        if value not in self.index:
            self.index[value] = len(self.strings)
            self.strings.append(value)
        return self.index[value]

    # ---------------- chunks ----------------

    def string_pool(self):
        data = b''
        offsets = []
        for st in self.strings:
            offsets.append(len(data))
            enc = st.encode('utf-16-le')
            data += struct.pack('<H', len(enc) // 2) + enc + b'\x00\x00'
        while len(data) % 4:
            data += b'\x00'
        count = len(self.strings)
        header = struct.pack('<HHI', RES_STRING_POOL, 28, 28 + 4 * count + len(data))
        header += struct.pack('<IIIII', count, 0, 0, 28 + 4 * count, 0)
        body = b''.join(struct.pack('<I', o) for o in offsets)
        return header + body + data

    def resource_map(self):
        ids = []
        for st in self.strings:
            ids.append(ATTR_IDS.get(st, 0))
        body = b''.join(struct.pack('<I', i) for i in ids)
        return struct.pack('<HHI', RES_XML_RESOURCE_MAP, 8, 8 + len(body)) + body

    def start_namespace(self, prefix, uri):
        return struct.pack('<HHIIIII',
                           RES_XML_START_NAMESPACE, 16, 24,
                           1, 0xFFFFFFFF, self.s(prefix), self.s(uri))

    def end_namespace(self, prefix, uri):
        return struct.pack('<HHIIIII',
                           RES_XML_END_NAMESPACE, 16, 24,
                           1, 0xFFFFFFFF, self.s(prefix), self.s(uri))

    def start_element(self, el, uri_index):
        raw_attrs = b''
        for a in el.attrs:
            ns = uri_index if a.ns_prefix == 'android' else 0xFFFFFFFF
            name_idx = self.s(a.name)
            if a.kind == 'str':
                val_idx = self.s(str(a.value))
                raw = val_idx
                data_type = TYPE_STRING
                data = val_idx
            elif a.kind == 'int':
                raw = 0xFFFFFFFF
                data_type = TYPE_INT_DEC
                data = int(a.value)
            elif a.kind == 'bool':
                raw = 0xFFFFFFFF
                data_type = TYPE_INT_BOOLEAN
                data = 0xFFFFFFFF if a.value else 0
            else:
                raise ValueError(a.kind)
            raw_attrs += struct.pack('<III', ns, name_idx, raw)
            raw_attrs += struct.pack('<HBBI', 8, 0, data_type, data & 0xFFFFFFFF)
        header_size = 16
        size = 16 + 20 + len(raw_attrs)
        head = struct.pack('<HHIII', RES_XML_START_ELEMENT, header_size, size, 1, 0xFFFFFFFF)
        ext = struct.pack('<IIHHHHHH', 0xFFFFFFFF, self.s(el.name),
                          20, 20, len(el.attrs), 0, 0, 0)
        return head + ext + raw_attrs

    def end_element(self, el):
        return struct.pack('<HHIIIII', RES_XML_END_ELEMENT, 16, 24, 1, 0xFFFFFFFF, 0xFFFFFFFF, self.s(el.name))

    def build(self, root, android_ns):
        # collect all strings first (order matters for resource map + indices)
        self.s('android')
        self.s(android_ns)
        self._collect(root)
        body = b''
        body += self.start_namespace('android', android_ns)
        body += self._emit(root, self.index[android_ns])
        body += self.end_namespace('android', android_ns)
        payload = self.string_pool() + self.resource_map() + body
        return struct.pack('<HHI', RES_XML_TYPE, 8, 8 + len(payload)) + payload

    def _collect(self, el):
        self.s(el.name)
        for a in el.attrs:
            self.s(a.name)
            if a.kind == 'str':
                self.s(str(a.value))
        for c in el.children:
            self._collect(c)

    def _emit(self, el, uri_index):
        out = self.start_element(el, uri_index)
        for c in el.children:
            out += self._emit(c, uri_index)
        out += self.end_element(el)
        return out


ANDROID_NS = 'http://schemas.android.com/apk/res/android'


def build_manifest(package, version_code, version_name, min_sdk, target_sdk, app_label, metas, launcher=True):
    """metas: list of (name, kind, value) where kind is 'str' | 'int' | 'bool'.

    aapt infers the type of <meta-data android:value>; LSPosed reads them with
    Bundle.getBoolean / getInt, so the typed value must match (a String "true"
    throws ClassCastException and the module gets filtered out).
    """
    meta_nodes = []
    for name, kind, value in metas:
        meta_nodes.append(Element('meta-data', [
            Attr('android', 'name', 'str', name),
            Attr('android', 'value', kind, value),
        ]))
    children = []
    if launcher:
        children.append(Element('activity', [
            Attr('android', 'name', 'str', '.MainActivity'),
            Attr('android', 'exported', 'bool', True),
        ], [
            Element('intent-filter', [], [
                Element('action', [Attr('android', 'name', 'str', 'android.intent.action.MAIN')]),
                Element('category', [Attr('android', 'name', 'str', 'android.intent.category.LAUNCHER')]),
            ]),
        ]))
    children.extend(meta_nodes)
    app = Element('application', [
        Attr('android', 'label', 'str', app_label),
        Attr('android', 'hasCode', 'bool', True),
    ], children)
    uses_sdk = Element('uses-sdk', [
        Attr('android', 'minSdkVersion', 'int', min_sdk),
        Attr('android', 'targetSdkVersion', 'int', target_sdk),
    ])
    manifest = Element('manifest', [
        Attr('', 'package', 'str', package),
        Attr('android', 'versionCode', 'int', version_code),
        Attr('android', 'versionName', 'str', version_name),
    ], [uses_sdk, app])
    w = Writer()
    return w.build(manifest, ANDROID_NS)


if __name__ == '__main__':
    data = build_manifest('com.dsh.gcblock', 1, '1.0', 24, 29, 'GCBlock',
                          [('xposedmodule', 'true')])
    open('/tmp/test_manifest.axml', 'wb').write(data)
    print('written', len(data))
