from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient

import main as embedding_server


@pytest.fixture(autouse=True)
def reset_model():
    """각 계약 테스트가 모델 전역 상태를 공유하지 않게 격리한다."""
    embedding_server.model = None
    yield
    embedding_server.model = None


@pytest.fixture
def client():
    return TestClient(embedding_server.app)


def test_embed_preserves_single_response_contract(client):
    fake_model = Mock()
    fake_model.encode.return_value = {"dense_vecs": [[0.1, 0.2]]}
    embedding_server.model = fake_model

    response = client.post("/embed", json={"text": "검색어"})

    assert response.status_code == 200
    assert response.json() == {"vector": [0.1, 0.2]}
    fake_model.encode.assert_called_once_with(["검색어"], batch_size=1, max_length=8192)


def test_embed_batch_preserves_input_order_and_indexes(client):
    fake_model = Mock()
    fake_model.encode.return_value = {"dense_vecs": [[0.1, 0.2], [0.3, 0.4]]}
    embedding_server.model = fake_model

    response = client.post(
        "/embed/batch",
        json={"texts": ["첫 번째", "두 번째"], "batch_size": 16},
    )

    assert response.status_code == 200
    assert response.json() == {
        "model": "BAAI/bge-m3",
        "embeddings": [
            {"index": 0, "vector": [0.1, 0.2]},
            {"index": 1, "vector": [0.3, 0.4]},
        ],
    }
    fake_model.encode.assert_called_once_with(
        ["첫 번째", "두 번째"], batch_size=16, max_length=8192
    )


@pytest.mark.parametrize(
    "payload",
    [
        {"texts": [], "batch_size": 16},
        {"texts": ["   "], "batch_size": 16},
        {"texts": ["본문"], "batch_size": 0},
        {"texts": ["본문"], "batch_size": 65},
    ],
)
def test_embed_batch_rejects_invalid_request(client, payload):
    embedding_server.model = Mock()

    response = client.post("/embed/batch", json=payload)

    assert response.status_code == 422
    embedding_server.model.encode.assert_not_called()


def test_embed_batch_returns_503_when_model_is_not_loaded(client):
    response = client.post(
        "/embed/batch",
        json={"texts": ["본문"], "batch_size": 1},
    )

    assert response.status_code == 503
    assert response.json() == {"detail": "Model not loaded"}


@pytest.mark.parametrize(
    "provider_result",
    [
        {},
        {"dense_vecs": [[0.1, 0.2]]},
    ],
)
def test_embed_batch_rejects_invalid_provider_result(client, provider_result):
    fake_model = Mock()
    fake_model.encode.return_value = provider_result
    embedding_server.model = fake_model

    response = client.post(
        "/embed/batch",
        json={"texts": ["첫 번째", "두 번째"], "batch_size": 2},
    )

    assert response.status_code == 500
    assert response.json() == {"detail": "Embedding generation failed"}
