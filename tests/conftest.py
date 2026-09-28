import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.environ["OPENAI_API_KEY"] = ""          # force the offline mock backend
os.environ["SWEEP_INTERVAL_SECONDS"] = "0"  # no background sweeper in tests

from support_router.config import Settings  # noqa: E402


@pytest.fixture
def settings(tmp_path):
    return Settings(openai_api_key="", db_path=str(tmp_path / "test.db"), app_api_key="test-key")
