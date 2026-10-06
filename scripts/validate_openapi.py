#!/usr/bin/env python3
"""Validate the checked-in API schema with the optional pinned verification dependencies."""
from pathlib import Path
import sys
try:
    import yaml
    from openapi_spec_validator import validate_spec
except ImportError:
    print('Install the optional dependencies: python -m pip install -r scripts/requirements-validation.txt', file=sys.stderr)
    raise SystemExit(2)

spec = Path(__file__).resolve().parents[1] / 'src/main/resources/static/openapi.yaml'
validate_spec(yaml.safe_load(spec.read_text()))
print('PASS: OpenAPI 3.0.3 schema validation using openapi-spec-validator 0.7.2')
