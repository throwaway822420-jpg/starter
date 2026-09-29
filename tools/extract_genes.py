#!/usr/bin/env python3
"""Find the real gene (DNA coding sequence) behind each preset chain and bake it into Kotlin.

For each preset chain: PDB entry -> UniProt accession (PDBe SIFTS), or a fixed accession for presets without a
structure -> the entry's EMBL/ENA coding sequences -> the first one whose translation contains the chain (up to
two differing residues, or 4 % as a last resort, e.g. the sickle-cell mutation, which get the nearest codon by fewest base changes).
Only the chain's own codons are kept, plus whether the real stop codon follows. Designed proteins have no gene
and are skipped; the app gives them codons by the host's usage.

Also downloads codon usage tables (Kazusa) for E. coli, human and yeast.
Run from the repo root:  python3 tools/extract_genes.py
"""
import json, re, sys, time, urllib.request

sys.path.insert(0, 'tools')
OUT = 'android/core/src/main/java/com/hydrophobiccollapse/Genes.kt'

CODE = {}
bases = 'TCAG'
aas = 'FFLLSSSSYY**CC*WLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSRRVVVVAAAADDEEGGGG'
k = 0
for a in bases:
    for b in bases:
        for c in bases:
            CODE[a + b + c] = aas[k]; k += 1

def translate(dna):
    return ''.join(CODE.get(dna[i:i + 3], 'X') for i in range(0, len(dna) - 2, 3))

def get(url, tries=3):
    for t in range(tries):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                return r.read().decode('utf-8', 'replace')
        except Exception as e:
            if t == tries - 1: raise
            time.sleep(2)

# Presets without a structure in extract_native.SOURCES, by UniProt accession
EXTRA = {'abeta': ['P05067'], 'abeta6': ['P05067'] * 6, 'abeta24': ['P05067'] * 24, 'oxytocin': ['P01178']}
# Designed proteins: no natural gene
DESIGNED = {'chignolin', 'trpcage', 'top7', 'ankyrin', 'ctpr3'}

def sifts(pdb):
    data = json.loads(get(f'https://www.ebi.ac.uk/pdbe/api/mappings/uniprot/{pdb.lower()}'))
    out = {}
    for acc, v in data[pdb.lower()]['UniProt'].items():
        for m in v['mappings']:
            out.setdefault(m['chain_id'], acc)
    return out

_entries = {}
def uniprot(acc):
    if acc not in _entries:
        _entries[acc] = json.loads(get(f'https://rest.uniprot.org/uniprotkb/{acc}?format=json'))
    return _entries[acc]

_cds = {}
def cds(pid):
    if pid not in _cds:
        fa = get(f'https://www.ebi.ac.uk/ena/browser/api/fasta/{pid}')
        _cds[pid] = ''.join(l.strip() for l in fa.splitlines() if not l.startswith('>')).upper()
    return _cds[pid]

def nearest_codon(orig, aa):
    best = None
    for c, a in CODE.items():
        if a != aa: continue
        d = sum(x != y for x, y in zip(c, orig))
        if best is None or d < best[0]: best = (d, c)
    return best[1]

def find(chain, acc, tol=2, limit=12):
    """(dna of the chain's codons, the stop codon right after it or "", protein id, residues changed) or None."""
    e = uniprot(acc)
    ids = []
    for x in e.get('uniProtKBCrossReferences', []):
        if x['database'] != 'EMBL': continue
        p = {q['key']: q['value'] for q in x.get('properties', [])}
        pid = p.get('ProteinId', '-')
        if pid == '-' or p.get('Status') not in (None, '-'): continue
        ids.append((0 if p.get('MoleculeType') == 'mRNA' else 1, pid))
    for _, pid in sorted(ids)[:limit]:
        try:
            dna = cds(pid)
        except Exception:
            continue
        for frame in range(3):
            prot = translate(dna[frame:])
            n = len(chain)
            for s in range(0, len(prot) - n + 1):
                window = prot[s:s + n]
                diff = [i for i in range(n) if window[i] != chain[i]]
                if len(diff) > tol: continue
                codons = [dna[frame + 3 * (s + i): frame + 3 * (s + i) + 3] for i in range(n)]
                for i in diff: codons[i] = nearest_codon(codons[i], chain[i])
                nxt = dna[frame + 3 * (s + n): frame + 3 * (s + n) + 3]
                return ''.join(codons), nxt if CODE.get(nxt) == '*' else '', pid, len(diff)
    return None

def kind(entry):
    lineage = entry.get('organism', {}).get('lineage', [])
    if 'Bacteria' in lineage or 'Viruses' in lineage: return 'B'
    if 'Fungi' in lineage: return 'Y'
    return 'H'

def usage(species):
    html = get(f'https://www.kazusa.or.jp/codon/cgi-bin/showcodon.cgi?species={species}&aa=1&style=N')
    pre = re.search(r'<PRE>(.*?)</PRE>', html, re.S).group(1)
    table = {}
    for codon, aa, frac in re.findall(r'([ACGU]{3}) (\S) ([0-9.]+)', pre):
        table[codon.replace('U', 'T')] = float(frac)
    assert len(table) == 64, len(table)
    return table

def main():
    import extract_native
    presets = extract_native.presets()
    lines = []
    report = []
    for pid, chains in presets.items():
        if pid in DESIGNED: continue
        if pid in extract_native.SOURCES:
            pdb, pchains = extract_native.SOURCES[pid]
            m = sifts(pdb)
            accs = [m.get(c) for c in pchains]
        elif pid in EXTRA:
            accs = EXTRA[pid]
        else:
            report.append(f'{pid}: no source'); continue
        recs = []
        for chain, acc in zip(chains, accs):
            if not acc: recs.append('null'); report.append(f'{pid}: chain without UniProt'); continue
            e = uniprot(acc)
            hit = find(chain, acc) or find(chain, acc, tol=max(5, len(chain) // 25), limit=60)
            if hit is None:
                recs.append('null'); report.append(f'{pid} {acc}: no matching coding sequence'); continue
            dna, stop, prot_id, mism = hit
            assert translate(dna) == chain, (pid, acc)
            gene = (e.get('genes') or [{}])[0].get('geneName', {}).get('value', acc)
            org = e.get('organism', {}).get('scientificName', '?')
            recs.append(f'GeneRecord("{dna}", "{prot_id}", "{gene}", "{org}", \'{kind(e)}\', "{stop}", {mism})')
            report.append(f'{pid}: {gene} ({org}) {prot_id} {len(chain)} codons, stop {stop or "-"}, changed {mism}')
        lines.append(f'        "{pid}" to listOf({", ".join(recs)}),')
        print(report[-1], file=sys.stderr)
    tables = {'ECOLI': usage(316407), 'HUMAN': usage(9606), 'YEAST': usage(4932)}
    kt = ['package com.hydrophobiccollapse', '',
          '// Generated by tools/extract_genes.py: the real coding sequence (ENA) behind each preset chain, and codon',
          '// usage tables (Kazusa: share of each amino acid\'s codons). Do not edit by hand.',
          'internal object Genes {',
          '    val byPreset: Map<String, List<GeneRecord?>> = mapOf(', *lines, '    )']
    for name, t in tables.items():
        body = ', '.join(f'"{c}" to {t[c]}' for c in sorted(t))
        kt.append(f'    val USAGE_{name}: Map<String, Double> = mapOf({body})')
    kt.append('}')
    open(OUT, 'w', encoding='utf-8', newline='\n').write('\n'.join(kt) + '\n')
    print('\n'.join(report))

main()
