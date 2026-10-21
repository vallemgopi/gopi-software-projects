"""Recompute a report or import a consented/de-identified JSON dataset."""
import argparse
import json
from pathlib import Path
from pydantic import BaseModel, Field, ConfigDict
from typing import Literal
from app import evaluate

class Fields(BaseModel):
    model_config = ConfigDict(extra='forbid')
    category: Literal['water','electricity','roads','sanitation'] | None
    summary: str | None
    location: str | None
    datetime: str | None
    identifier: str | None

class Example(BaseModel):
    model_config = ConfigDict(extra='forbid')
    id: str
    synthetic: bool
    language: str
    reference_transcript: str
    observed_transcript: str = Field(min_length=1, max_length=10000)
    fields: Fields

class Dataset(BaseModel):
    model_config = ConfigDict(extra='forbid')
    version: str
    synthetic: bool
    examples: list[Example] = Field(min_length=1, max_length=10000)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--dataset', default=str(Path(__file__).parent/'data/evaluation-v2.json'))
    parser.add_argument('--output', default='report.json')
    args = parser.parse_args()
    dataset = Dataset.model_validate_json(Path(args.dataset).read_text(encoding='utf-8'))
    ids = [e.id for e in dataset.examples]
    if len(ids) != len(set(ids)):
        parser.error('Example IDs must be unique')
    Path(args.output).write_text(json.dumps(evaluate(dataset.model_dump()), indent=2), encoding='utf-8')
