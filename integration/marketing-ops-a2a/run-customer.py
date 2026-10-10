"""Create, publish and launch one marketing customer; the platform owns subsequent work."""

import json
import os
from pathlib import Path
from uuid import uuid4

from astraform_platform_client import ApiClient, Configuration
from astraform_platform_client.exceptions import ApiException
from astraform_platform_client.api.agent_catalog_api import AgentCatalogApi
from astraform_platform_client.api.agent_journeys_api import AgentJourneysApi
from astraform_platform_client.models.agent_definition_create_request import AgentDefinitionCreateRequest
from astraform_platform_client.models.agent_definition_publish_request import AgentDefinitionPublishRequest
from astraform_platform_client.models.agent_journey_launch_request import AgentJourneyLaunchRequest



def main():
    directory = Path(__file__).resolve().parent
    api = os.environ.get("ASTRAFORM_API_BASE", "http://localhost:8384").rstrip("/")
    # Accept the documented older HTTP base settings; generated clients take the origin.
    for suffix in ("/api/dashboard", "/api"):
        if api.endswith(suffix):
            api = api[:-len(suffix)]
            break
    client = ApiClient(Configuration(host=api, retries=0))
    catalog_api = AgentCatalogApi(client)
    sample_path = Path(os.environ.get("MARKETING_SAMPLE_FILE", directory / "sample-configuration.json"))
    sample = json.loads(sample_path.read_text())
    catalog = sample["catalogDraftRequest"]
    catalog["requestId"] = str(uuid4())
    catalog["agentKey"] = "marketing-customer-" + uuid4().hex[:12]
    catalog["draft"]["execution"]["modelBindingKey"] = os.environ.get(
        "CUSTOMER_MODEL_BINDING_KEY", catalog["draft"]["execution"]["modelBindingKey"])
    definition = json.loads(catalog_api.create_agent_definition(
        AgentDefinitionCreateRequest.from_dict(catalog), _request_timeout=60.0).to_json())["definition"]
    published = json.loads(catalog_api.publish_agent_definition(
        definition["catalogAgentId"], AgentDefinitionPublishRequest.from_dict({
            "expectedDraftVersion": definition["draftVersion"],
            "expectedDraftSpecDigest": definition["draftSpecDigest"]}),
        _request_timeout=60.0).to_json())["revision"]
    launch = sample["launchRequestTemplate"]
    launch.update({"requestId": str(uuid4()), "catalogAgentId": definition["catalogAgentId"],
                   "catalogRevisionId": published["catalogRevisionId"],
                   "catalogDefinitionDigest": published["catalogDefinitionDigest"]})
    launch["domain"]["bindingKey"] = os.environ.get("MARKETING_DOMAIN_BINDING_KEY", launch["domain"]["bindingKey"])
    launch["partnerTargetAgentKey"] = os.environ.get("CREATIVE_AGENCY_TARGET_KEY", launch["partnerTargetAgentKey"])
    result = json.loads(AgentJourneysApi(client).launch_agent_journey(
        AgentJourneyLaunchRequest.from_dict(launch), _request_timeout=60.0).to_json())
    print(json.dumps({"catalogAgentId": definition["catalogAgentId"],
                      "catalogRevisionId": published["catalogRevisionId"],
                      "launchRequest": launch, "launch": result}, indent=2))


if __name__ == "__main__":
    try:
        main()
    except ApiException as error:
        raise SystemExit(f"Astraform API: HTTP {error.status}: {error.body}") from None
